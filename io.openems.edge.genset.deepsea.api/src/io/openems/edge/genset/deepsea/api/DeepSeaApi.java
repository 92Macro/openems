package io.openems.edge.genset.deepsea.api;

import org.osgi.annotation.versioning.ProviderType;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerDoc;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.startstop.StartStoppable;


@ProviderType
public interface DeepSeaApi extends StartStoppable,  OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		
		/**
		 * Read/Set Active Power Limit.
		 * Load Level Setting kW
		 * (for fixed export)
		 * <ul>
		 * <li>Interface: DeepSea8660
		 * <li>Type: Integer
		 * <li>Unit: W
		 * </ul>
		 * All wrong!!! Need to do debug channel instead
		 */
		ACTIVE_POWER_LIMIT(new IntegerDoc() //
				.unit(Unit.PERCENT) //
				.accessMode(AccessMode.READ_WRITE) //
				.persistencePriority(PersistencePriority.MEDIUM) //
				.onInit(channel -> { //
					// on each Write to the channel -> set the value
					((IntegerWriteChannel) channel).onSetNextWrite(value -> {
						channel.setNextValue(value);
					});
				})),
		/**
		 * Read/Set Reactive Power Limit.
		 * Load Level Setting kVAr
		 * (for fixed export) 
		 * <ul>
		 * <li>Interface: DeepSea8660
		 * <li>Type: Integer
		 * <li>Unit: W
		 * </ul>
		 */
		REACTIVE_POWER_LIMIT(new IntegerDoc() //
				.unit(Unit.PERCENT) //
				.accessMode(AccessMode.READ_WRITE) //
				.persistencePriority(PersistencePriority.MEDIUM) //
				.onInit(channel -> { //
					// on each Write to the channel -> set the value
					((IntegerWriteChannel) channel).onSetNextWrite(value -> {
						channel.setNextValue(value);
					});
				})),
		
		/**
		 * Read/Set bus or mains mode.
		 * Bus / mains mode
		 *
		 * <ul>
		 * <li>Interface: DeepSea8660
		 * <li>Type: Integer
		 * <li>Unit: NA
		 * </ul>
		 */
		BUS_OR_MAINS_MODE(new IntegerDoc() //
				.accessMode(AccessMode.READ_WRITE) //
				.persistencePriority(PersistencePriority.MEDIUM) //
				.onInit(channel -> { //
					// on each Write to the channel -> set the value
					((IntegerWriteChannel) channel).onSetNextWrite(value -> {
						channel.setNextValue(value);
					});
				})),

		
		/**
		 * Enable / Disable generator using Remote control source 1.
		 *
		 * <ul>
		 * <li>Interface: DeepSea8660
		 * <li>Type: Integer
		 * <li>Range: 0 - 1
		 * </ul>
		 */
		REMOTE_CONTROL_SOURCE_1(new IntegerDoc() //
				.accessMode(AccessMode.READ_WRITE) //
				.persistencePriority(PersistencePriority.MEDIUM) //
				.onInit(channel -> { //
					// on each Write to the channel -> set the value
					((IntegerWriteChannel) channel).onSetNextWrite(value -> {
						channel.setNextValue(value);
					});
				}))

		
		/* @TODO: suggestions for other channels:
		 * Active Power
		 * Reactive Power
		 * Fuel level
		 * Frequency
		 * 
		 * 
		 */
		;

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}
	/**
	 * Gets the Channel for {@link ChannelId#ACTIVE_POWER_LIMIT}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getActivePowerLimitChannel() {
		return this.channel(ChannelId.ACTIVE_POWER_LIMIT);
	}

	/**
	 * Gets the Genset ActivePowerLimit. See {@link ChannelId#ACTIVE_POWER_LIMIT}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getActivePowerLimit() {
		return this.getActivePowerLimitChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#ACTIVE_POWER_LIMIT}
	 * Channel.
	 *
	 * @param value the next value
	 */
	public default void _setActivePowerLimit(Integer value) {
		this.getActivePowerLimitChannel().setNextValue(value);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#ACTIVE_POWER_LIMIT}
	 * Channel.
	 *
	 * @param value the next value
	 */
	public default void _setActivePowerLimit(int value) {
		this.getActivePowerLimitChannel().setNextValue(value);
	}
	
	/**
	 * Gets the Channel for {@link ChannelId#REACTIVE_POWER_LIMIT}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getReactivePowerLimitChannel() {
		return this.channel(ChannelId.REACTIVE_POWER_LIMIT);
	}

	/**
	 * Gets the Genset ReactivePowerLimit. See {@link ChannelId#REACTIVE_POWER_LIMIT}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getReactivePowerLimit() {
		return this.getReactivePowerLimitChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#REACTIVE_POWER_LIMIT}
	 * Channel.
	 *
	 * @param value the next value
	 */
	public default void _setReactivePowerLimit(Integer value) {
		this.getReactivePowerLimitChannel().setNextValue(value);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#REACTIVE_POWER_LIMIT}
	 * Channel.
	 *
	 * @param value the next value
	 */
	public default void _setReactivePowerLimit(int value) {
		this.getReactivePowerLimitChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#BUS_OR_MAINS_MODE}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getBusOrMainsModeChannel() {
		return this.channel(ChannelId.BUS_OR_MAINS_MODE);
	}

	/**
	 * Gets the Genset BusOrMainsMode. See {@link ChannelId#BUS_OR_MAINS_MODE}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getBusOrMainsMode() {
		return this.getBusOrMainsModeChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#BUS_OR_MAINS_MODE}
	 * Channel.
	 *
	 * @param value the next value
	 */
	public default void _setBusOrMainsMode(Integer value) {
		this.getBusOrMainsModeChannel().setNextValue(value);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#BUS_OR_MAINS_MODE}
	 * Channel.
	 *
	 * @param value the next value
	 */
	public default void _setBusOrMainsMode(int value) {
		this.getBusOrMainsModeChannel().setNextValue(value);
	}
	
	/**
	 * Gets the Channel for {@link ChannelId#REMOTE_CONTROL_SOURCE_1}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getRemoteControlSource1Channel() {
		return this.channel(ChannelId.REMOTE_CONTROL_SOURCE_1);
	}

	/**
	 * Gets the Genset RemoteControlSource1. See {@link ChannelId#REMOTE_CONTROL_SOURCE_1}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getRemoteControlSource1() {
		return this.getRemoteControlSource1Channel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#REMOTE_CONTROL_SOURCE_1}
	 * Channel.
	 *
	 * @param value the next value
	 */
	public default void _setRemoteControlSource1(Integer value) {
		this.getRemoteControlSource1Channel().setNextValue(value);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#REMOTE_CONTROL_SOURCE_1}
	 * Channel.
	 *
	 * @param value the next value
	 */
	public default void _setRemoteControlSource1(int value) {
		this.getRemoteControlSource1Channel().setNextValue(value);
	}
}
