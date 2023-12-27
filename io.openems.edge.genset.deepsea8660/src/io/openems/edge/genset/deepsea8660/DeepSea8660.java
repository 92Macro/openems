package io.openems.edge.genset.deepsea8660;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.loadshedding.api.Loadshedding.ChannelId;


public interface DeepSea8660 extends OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		
		GRID_FREQUENCY(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_ONLY).unit(Unit.MILLIHERTZ)), //
		GRID_L1_POWER(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_ONLY).unit(Unit.KILOWATT)), //
		GRID_L2_POWER(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_ONLY).unit(Unit.KILOWATT)), //		
		GRID_L3_POWER(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_ONLY).unit(Unit.KILOWATT)), //	
		GRID_TOTAL_POWER(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_ONLY).unit(Unit.KILOWATT)), //	
					
		SYSTEM_CONTROL_KEY(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.WRITE_ONLY)), //	
		
		SYSTEM_CONTROL_KEY_COMPLIMENT(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.WRITE_ONLY)), //	
		
		ACTIVE_POWER_LIMIT(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_WRITE).unit(Unit.PERCENT)), //
		REACTIVE_POWER_LIMIT(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_WRITE).unit(Unit.PERCENT)), //
		BUS_OR_MAINS_MODE(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_WRITE)), //
		REMOTE_CONTROL_SOURCE_1(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_WRITE)), //
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
	 * Gets the Loadshedding stage. See {@link ChannelId#ACTIVE_POWER_LIMIT}.
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
	 * Gets the Loadshedding stage. See {@link ChannelId#REACTIVE_POWER_LIMIT}.
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
	 * Gets the Loadshedding stage. See {@link ChannelId#BUS_OR_MAINS_MODE}.
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
	 * Gets the Loadshedding stage. See {@link ChannelId#REMOTE_CONTROL_SOURCE_1}.
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
	public default void _setRemoteControlSource1(int value) {
		this.getRemoteControlSource1Channel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#GRID_FREQUENCY}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getGridFrequencyChannel() {
		return this.channel(ChannelId.GRID_FREQUENCY);
	}

	/**
	 * Gets the Grid Frequency. See {@link ChannelId#GRID_FREQUENCY}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getGridFrequency() {
		return this.getGridFrequencyChannel().value();
	}
	
	/**
	 * Gets the Channel for {@link ChannelId#GRID_TOTAL_POWER}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getGridTotalPowerChannel() {
		return this.channel(ChannelId.GRID_TOTAL_POWER);
	}

	/**
	 * Gets the Grid Total Power. See {@link ChannelId#GRID_TOTAL_POWER}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getGridTotalPower() {
		return this.getGridTotalPowerChannel().value();
	}
	
	/**
	 * Gets the Channel for {@link ChannelId#SYSTEM_CONTROL_KEY}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getSystemControlKeyChannel() {
		return this.channel(ChannelId.SYSTEM_CONTROL_KEY);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#SYSTEM_CONTROL_KEY}
	 * Channel.
	 *
	 * @param value the next value
	 */
	public default void _setSystemControlKey(int value) {
		this.getSystemControlKeyChannel().setNextValue(value);
	}
	
	/**
	 * Gets the Channel for {@link ChannelId#SYSTEM_CONTROL_KEY_COMPLIMENT}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getSystemControlKeyComplimentChannel() {
		return this.channel(ChannelId.SYSTEM_CONTROL_KEY_COMPLIMENT);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#SYSTEM_CONTROL_KEY_COMPLIMENT}
	 * Channel.
	 *
	 * @param value the next value
	 */
	public default void _setSystemControlKeyCompliment(int value) {
		this.getSystemControlKeyComplimentChannel().setNextValue(value);
	}
}
