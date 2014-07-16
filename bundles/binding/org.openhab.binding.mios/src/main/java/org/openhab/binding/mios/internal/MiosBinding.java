/**
 * Copyright (c) 2010-2014, openHAB.org and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.mios.internal;

import java.util.Calendar;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import org.openhab.binding.mios.MiosBindingProvider;
import org.openhab.binding.mios.internal.config.MiosBindingConfig;
import org.openhab.core.binding.AbstractActiveBinding;
import org.openhab.core.binding.BindingProvider;
import org.openhab.core.items.Item;
import org.openhab.core.library.items.ContactItem;
import org.openhab.core.library.items.DateTimeItem;
import org.openhab.core.library.items.DimmerItem;
import org.openhab.core.library.items.NumberItem;
import org.openhab.core.library.items.RollershutterItem;
import org.openhab.core.library.items.SwitchItem;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.OpenClosedType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.openhab.core.types.TypeParser;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This refresh service for the MiOS binding is used to periodically check to
 * ensure all MiOS connections are still open and alive.
 * 
 * All item updates are received asynchronously via the web socket All item
 * commands are sent via the web socket
 * 
 * @author Mark Clark
 * @since 1.6.0
 */
public class MiosBinding extends AbstractActiveBinding<MiosBindingProvider>
		implements ManagedService {

	private static final Logger logger = LoggerFactory
			.getLogger(MiosBinding.class);

	private Map<String, MiosConnector> connectors = new HashMap<String, MiosConnector>();
	private Map<String, MiosUnit> nameUnitMapper = null;

	/**
	 * The refresh interval used to check for lost connections.
	 */
	private long refreshInterval = 10000;

	public void activate() {
		logger.debug(getName() + " activate()");
		setProperlyConfigured(true);
	}

	public void deactivate() {
		logger.debug(getName() + " deactivate()");

		// close any open connections
		for (MiosConnector connector : connectors.values()) {
			if (connector.isConnected()) {
				connector.close();
			}
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected long getRefreshInterval() {
		return refreshInterval;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected String getName() {
		return "MiOS Refresh Service";
	}

	/**
	 * @{inheritDoc
	 */
	@Override
	public void bindingChanged(BindingProvider provider, String itemName) {
		logger.debug("bindingChanged: start provider '{}', itemName '{}'",
				provider, itemName);

		if (provider instanceof MiosBindingProvider) {
			registerWatch((MiosBindingProvider) provider, itemName);
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void allBindingsChanged(BindingProvider provider) {
		logger.debug("allBindingsChanged: start provider '{}'", provider);

		if (provider instanceof MiosBindingProvider) {
			MiosBindingProvider miosProvider = (MiosBindingProvider) provider;

			for (String itemName : provider.getItemNames()) {
				registerWatch(miosProvider, itemName);
			}
		}
	}

	private void registerAllWatches() {
		logger.debug("registerAllWatches: start");

		for (BindingProvider provider : providers) {
			logger.debug("registerAllWatches: provider '{}'",
					provider.getClass());

			if (provider instanceof MiosBindingProvider) {
				MiosBindingProvider miosProvider = (MiosBindingProvider) provider;

				for (String itemName : provider.getItemNames()) {
					registerWatch(miosProvider, itemName);
				}
			}
		}
	}

	private void registerWatch(MiosBindingProvider miosProvider, String itemName) {
		logger.debug("registerWatch: start miosProvider '{}', itemName '{}'",
				miosProvider, itemName);

		String unitName = miosProvider.getMiosUnitName(itemName);

		// Minimally we need to do this part, so that the MiosConnector objects
		// get brought into existence (and threads started)
		// Joy! Getters with side-effects.

		// TODO: Work out a cleaner entry point for the Child connections to get
		// started.
		MiosConnector connector = getMiosConnector(unitName);
	}

	public String getMiosUnitName(String itemName) {
		logger.trace("getMiosUnitName: start itemName '{}'", itemName);

		for (BindingProvider provider : providers) {
			if (provider instanceof MiosBindingProvider) {
				if (provider.getItemNames().contains(itemName)) {
					return ((MiosBindingProvider) provider)
							.getMiosUnitName(itemName);
				}
			}
		}
		return null;
	}

	public String getProperty(String itemName) {
		logger.trace("getProperty: start itemName '{}'", itemName);

		for (BindingProvider provider : providers) {
			if (provider instanceof MiosBindingProvider) {
				if (provider.getItemNames().contains(itemName)) {
					return ((MiosBindingProvider) provider)
							.getProperty(itemName);
				}
			}
		}
		return null;
	}

	private MiosConnector getMiosConnector(String unitName) {
		logger.trace("getMiosConnector: start unitName '{}'", unitName);

		// sanity check
		if (unitName == null)
			return null;

		// check if the connector for this unit already exists
		MiosConnector connector = connectors.get(unitName);
		if (connector != null)
			return connector;

		MiosUnit miosUnit;

		// NOTE: We deviate from the XBMC Binding, in that we only accept
		// "names" presented in the openHAB configuration files.

		// check if we have been initialized yet - can't process
		// named units until we have read the binding config.
		if (nameUnitMapper == null) {
			logger.trace(
					"Attempting to access the named MiOS Unit '{}' before the binding config has been loaded",
					unitName);
			return null;
		}

		miosUnit = nameUnitMapper.get(unitName);

		// Check this Unit name exists in our config
		if (miosUnit == null) {
			logger.error(
					"Named MiOS Unit '{}' does not exist in the binding config",
					unitName);
			return null;
		}

		// create a new connection handler
		logger.debug("Creating new MiosConnector for '{}' on {}", unitName,
				miosUnit.getHostname());
		connector = new MiosConnector(miosUnit, this);
		connectors.put(unitName, connector);

		// attempt to open the connection straight away
		try {
			connector.open();
		} catch (Exception e) {
			logger.error("Connection failed for '{}' on {}", unitName,
					miosUnit.getHostname());
		}

		return connector;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected void execute() {
		for (Map.Entry<String, MiosConnector> entry : connectors.entrySet()) {
			MiosConnector connector = entry.getValue();

			if (!connector.isConnected()) {
				// broken connection so attempt to reconnect
				logger.debug(
						"Broken connection found for '{}', attempting to reconnect...",
						entry.getKey());
			}

			connector.pollUnit();
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected void internalReceiveCommand(String itemName, Command command) {
		try {
			logger.debug("internalReceiveCommand: itemName '{}', command '{}'",
					itemName, command);

			// Lookup the MiOS Unit name and property for this item
			String unitName = getMiosUnitName(itemName);

			MiosConnector connector = getMiosConnector(unitName);
			if (connector == null) {
				logger.warn(
						"Received command ({}) for item '{}' but no connector found for MiOS Unit '{}', ignoring",
						new Object[] { command.toString(), itemName, unitName });
				return;
			}

			if (!connector.isConnected()) {
				logger.warn(
						"Received command ({}) for item '{}' but the connection to the MiOS Unit '{}' is down, ignoring",
						new Object[] { command.toString(), itemName, unitName });
				return;
			}

			for (BindingProvider provider : providers) {
				if (provider instanceof MiosBindingProvider) {
					MiosBindingConfig config = ((MiosBindingProviderImpl) provider)
							.getMiosBindingConfig(itemName);

					// TODO: Work out how to retrieve an Item's current state,
					// so it can be referenced in the invokeCommand.
					if (config != null) {
						connector.invokeCommand(config, command, null);
					} else {
						logger.trace(
								"internalReceiveCommand: Found null BindingConfig for item '{}' command '{}'",
								itemName, command);
					}
				}
			}

		} catch (Exception e) {
			logger.error("Error handling command", e);
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected void internalReceiveUpdate(String itemName, State newState) {
		try {
			logger.trace(
					"internalReceiveUpdate: itemName '{}', newState '{}', class '{}'",
					new Object[] { itemName, newState, newState.getClass() });

			// Lookup the MiOS Unit name and property for this item
			String unitName = getMiosUnitName(itemName);

			MiosConnector connector = getMiosConnector(unitName);
			if (connector == null) {
				logger.warn(
						"Received update '{}' for item '{}' but no connector found for MiOS Unit '{}', ignoring",
						new Object[] { newState.toString(), itemName, unitName });
				return;
			}

			if (!connector.isConnected()) {
				logger.warn(
						"Received update '{}' for item '{}' but the connection to the MiOS Unit '{}' is down, ignoring",
						new Object[] { newState.toString(), itemName, unitName });
				return;
			}

			// TODO: Once we have Bindings that have values that go "back" to
			// MiOS we need to implement this method. At that time, we'll also
			// need to work out which direction(s) the Binding can handle and
			// not send stuff back to the MiOS Unit when we just got it from the
			// MiOS Unit. In this case, it may be simpler not to register
			// interest in this callback if we know that the specific MiOS
			// BindingConfig cannot support it.
			//
			// ie. Ask the MiOSBindingConfig whether we need to do anything.
			//
			// The other use-case is some sort of data-type coercion. If the
			// Item being bound is "DecimalType" and we get a "String" (as is
			// COMMON in MiOS) then we can attempt to convert it before
			// "stuffing it back down".

		} catch (Exception e) {
			logger.error("Error handling update", e);
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void updated(Dictionary<String, ?> config)
			throws ConfigurationException {
		logger.trace(getName() + " updated()");

		Map<String, MiosUnit> units = new HashMap<String, MiosUnit>();

		Enumeration<String> keys = config.keys();

		while (keys.hasMoreElements()) {
			String key = keys.nextElement();

			if ("service.pid".equals(key)) {
				continue;
			}

			// Only apply this pattern once, as the leading-edge may not be the
			// name of a unit. We support two forms:
			//
			// mios:lounge.host=...
			// mios:host=
			//
			// The latter form refers to the "default" Unit, which can be used
			// in bindings to make things simpler for single-unit owners.
			//
			String unitName = null;
			String value = ((String) config.get(key)).trim();
			String[] parts = key.split("\\.", 2);

			if (parts.length != 1) {
				unitName = parts[0];
				key = parts[1];
			}

			boolean created = false;
			String hackUnitName = (unitName == null) ? "_default" : unitName;
			MiosUnit unit = units.get(hackUnitName);

			if (unit == null) {
				unit = new MiosUnit(unitName);
				created = true;
			}

			if ("host".equals(key)) {
				unit.setHostname(value);
			} else if ("port".equals(key)) {
				unit.setPort(Integer.valueOf(value));
			} else if ("timeout".equals(key)) {
				unit.setTimeout(Integer.valueOf(value));
			} else {
				logger.warn("Unexpected configuration parameter {}", key);
				created = false;
			}

			// Only bother to put it back if we created a new one, otherwise
			// it's already there!
			if (created) {
				logger.debug("updated: Created Unit '{}'", hackUnitName);
				units.put(hackUnitName, unit);
			}
		}

		nameUnitMapper = units;
		registerAllWatches();
	}

	/**
	 * Returns a {@link State} which is inherited from the {@link Item}s
	 * accepted DataTypes. The call is delegated to the {@link TypeParser}. If
	 * <code>item</code> is <code>null</code> the {@link StringType} is used.
	 * 
	 * Code borrowed from {@link HttpBinding}.
	 * 
	 * @param itemType
	 * @param value
	 * 
	 * @return a {@link State} which type is inherited by the {@link TypeParser}
	 *         or a {@link StringType} if <code>item</code> is <code>null</code>
	 */
	private State createState(Class<? extends Item> itemType, String value) {
		State result;
		try {
			if (itemType.isAssignableFrom(NumberItem.class)) {
				result = DecimalType.valueOf(value);
			} else if (itemType.isAssignableFrom(ContactItem.class)) {
				result = OpenClosedType.valueOf(value);
			} else if (itemType.isAssignableFrom(SwitchItem.class)) {
				result = OnOffType.valueOf(value);
			} else if (itemType.isAssignableFrom(DimmerItem.class)) {
				result = PercentType.valueOf(value);
			} else if (itemType.isAssignableFrom(RollershutterItem.class)) {
				result = PercentType.valueOf(value);
			} else if (itemType.isAssignableFrom(DateTimeItem.class)) {
				result = DateTimeType.valueOf(value);
			} else {
				result = StringType.valueOf(value);
			}

			logger.trace(
					"createState: Converted '{}' to '{}', bound to '{}'",
					new Object[] { value, result.getClass().getName(), itemType });

			return result;
		} catch (Exception e) {
			logger.debug("Couldn't create state of type '{}' for value '{}'",
					itemType, value);
			return StringType.valueOf(value);
		}
	}

	/**
	 * Push a value into all openHAB Bindings that match a given MiOS property
	 * name (from the binding).
	 * 
	 * In the process, this routine will perform Datatype conversions from Java
	 * types to openHAB's type system. These conversions are as follows:
	 * 
	 * <ul>
	 * <li>String -> StringType
	 * <li>Integer -> DecimalType
	 * <li>Double -> DecimalType
	 * <li>Boolean -> StringType (true == ON, false == OFF)
	 * <li>Calendar -> DateTimeType
	 * </ul>
	 * 
	 * @param property
	 *            the MiOS property name
	 * @param value
	 *            the value to push, can be Double/String/Calendar (only)
	 * @exception IllegalArgumentException
	 *                thrown if the value isn't one of the supported types.
	 */
	public void postPropertyUpdate(String property, Object value)
			throws Exception {
		if (value instanceof String) {
			internalPropertyUpdate(property, new StringType(value == null ? ""
					: (String) value));
		} else if (value instanceof Integer) {
			internalPropertyUpdate(property, new DecimalType((Integer) value));
		} else if (value instanceof Calendar) {
			internalPropertyUpdate(property, new DateTimeType((Calendar) value));
		} else if (value instanceof Double) {
			internalPropertyUpdate(property, new DecimalType((Double) value));
		} else if (value instanceof Boolean) {
			postPropertyUpdate(property,
					((Boolean) value).booleanValue() ? OnOffType.ON.toString()
							: OnOffType.OFF.toString());
		} else {
			throw new IllegalArgumentException("Unexpected Datatype, property="
					+ property + " datatype=" + value.getClass());
		}
	}

	private void internalPropertyUpdate(String property, State value)
			throws Exception {
		int bound = 0;

		if (value == null) {
			logger.trace("Value is null for Property '{}', ignored.", property);
			return;
		}

		for (BindingProvider provider : providers) {
			if (provider instanceof MiosBindingProvider) {
				MiosBindingProviderImpl miosProvider = (MiosBindingProviderImpl) provider;

				for (String itemName : miosProvider
						.getItemsForProperty(property)) {

					MiosBindingConfig config = miosProvider
							.getMiosBindingConfig(itemName);

					if (config != null) {
						if (config.supportsTransformIn()) {
							// Transform whatever value we have, based upon the
							// Transformation Service specified in the Binding
							// Config.

							State newValue = createState(config.getItemType(),
									config.transformIn(value.toString()));

							logger.trace(
									"internalPropertyUpdate: transformation present, from '{}' to '{}'",
									value, newValue);

							value = newValue;
						}

						// TODO Seriously? Reduce the overall number of type
						// conversions, it's crazy wasteful. Must have been
						// listening to too much Crystal Method at the time!!
						if (value instanceof StringType) {
							value = createState(config.getItemType(),
									value.toString());

							logger.trace(
									"internalPropertyUpdate: Converted value '{}' from StringType to '{}'",
									value, value.getClass());
						}

						logger.debug(
								"internalProperyUpdate: About to update itemName '{}' with value '{}'",
								itemName, value);

						eventPublisher.postUpdate(itemName, value);
						bound++;
					} else {
						logger.trace(
								"internalPropertyUpdate: Found null BindingConfig for item '{}' property '{}'",
								itemName, property);
					}
				}
			}
		}

		if (bound == 0) {
			logger.trace(
					"internalPropertyUpdate: NOT BOUND {mios=\"{}\"}, value={}",
					property, value);
		} else {
			logger.debug(
					"internalPropertyUpdate: BOUND {mios=\"{}\"}, value={}, bound {} time(s)",
					new Object[] { property, value, bound });
		}
	}
}
