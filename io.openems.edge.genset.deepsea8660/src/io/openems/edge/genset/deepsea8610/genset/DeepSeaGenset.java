package io.openems.edge.genset.deepsea8610.genset;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;


public interface DeepSeaGenset extends OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		GENSET_FREQUENCY(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_ONLY).unit(Unit.MILLIHERTZ)), //

		GENSET_L1_N_VOLTAGE(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_ONLY).unit(Unit.VOLT)), //
		GENSET_L2_N_VOLTAGE(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_ONLY).unit(Unit.VOLT)), //		
		GENSET_L3_N_VOLTAGE(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_ONLY).unit(Unit.VOLT)), //	

		GENSET_L1_POWER(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_ONLY).unit(Unit.KILOWATT)), //
		GENSET_L2_POWER(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_ONLY).unit(Unit.KILOWATT)), //		
		GENSET_L3_POWER(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_ONLY).unit(Unit.KILOWATT)), //	
		
		GENSET_TOTAL_POWER(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_ONLY).unit(Unit.KILOWATT)), //	
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
	 * Gets the Channel for {@link ChannelId#GENSET_FREQUENCY}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getGensetFrequencyChannel() {
		return this.channel(ChannelId.GENSET_FREQUENCY);
	}

	/**
	 * Gets the Loadshedding stage. See {@link ChannelId#GRID_FREQUENCY}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getGensetFrequency() {
		return this.getGensetFrequencyChannel().value();
	}
	
	/**
	 * Gets the Channel for {@link ChannelId#GENSET_TOTAL_POWER}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getGensetTotalPowerChannel() {
		return this.channel(ChannelId.GENSET_TOTAL_POWER);
	}

	/**
	 * Gets the Gesnet Total Power. See {@link ChannelId#GENSET_TOTAL_POWER}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getGensetTotalPower() {
		return this.getGensetTotalPowerChannel().value();
	}
}
