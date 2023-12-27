package io.openems.edge.loadshedding.api;

import org.osgi.annotation.versioning.ProviderType;

import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.value.Value;


@ProviderType
public interface Loadshedding extends OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		/**
		 * Stage.
		 *
		 * <ul>
		 * <li>Interface: Loadshedding
		 * <li>Type: Integer
		 * <li>Unit: NA
		 * </ul>
		 */
		STAGE(Doc.of(OpenemsType.INTEGER)//
				.text("Stores the current loadshedding stage")),

		
		/**
		 * TimeToShedStart.
		 *
		 * <ul>
		 * <li>Interface: Loadshedding
		 * <li>Type: Integer
		 * <li>Unit: Seconds
		 * </ul>
		 */
		TIME_OF_SHED_START(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.SECONDS)),//		
		
		/**
		 * TimeToShedEnd.
		 *
		 * <ul>
		 * <li>Interface: Loadshedding
		 * <li>Type: Integer
		 * <li>Unit: Seconds
		 * </ul>
		 */
		TIME_OF_SHED_END(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.SECONDS))	
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
	 * Gets the Channel for {@link ChannelId#STAGE}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getStageChannel() {
		return this.channel(ChannelId.STAGE);
	}

	/**
	 * Gets the Loadshedding stage. See {@link ChannelId#STAGE}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getStage() {
		return this.getStageChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#STAGE}
	 * Channel.
	 *
	 * @param value the next value
	 */
	public default void _setStage(Integer value) {
		this.getStageChannel().setNextValue(value);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#STAGE}
	 * Channel.
	 *
	 * @param value the next value
	 */
	public default void _setStage(int value) {
		this.getStageChannel().setNextValue(value);
	}
	
	/**
	 * Gets the Channel for {@link ChannelId#TIMETOSHEDSTART}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getTimeOfShedStartChannel() {
		return this.channel(ChannelId.TIME_OF_SHED_START);
	}

	/**
	 * Gets the Loadshedding stage. See {@link ChannelId#TIMETOSHEDSTART}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getTimeOfShedStart() {
		return this.getTimeOfShedStartChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#TIMETOSHEDSTART}
	 * Channel.
	 *
	 * @param value the next value
	 */
	public default void _setTimeOfShedStart(Integer value) {
		this.getTimeOfShedStartChannel().setNextValue(value);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#TIMETOSHEDSTART}
	 * Channel.
	 *
	 * @param value the next value
	 */
	public default void _setTimeOfShedStart(int value) {
		this.getTimeOfShedStartChannel().setNextValue(value);
	}	
	
	/**
	 * Gets the Channel for {@link ChannelId#TIMETOSHEDEND}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getTimeOfShedEndChannel() {
		return this.channel(ChannelId.TIME_OF_SHED_END);
	}

	/**
	 * Gets the Loadshedding stage. See {@link ChannelId#TIMETOSHEDEND}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getTimeOfShedEnd() {
		return this.getTimeOfShedEndChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#TIMETOSHEDEND}
	 * Channel.
	 *
	 * @param value the next value
	 */
	public default void _setTimeOfShedEnd(Integer value) {
		this.getTimeOfShedEndChannel().setNextValue(value);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#TIMETOSHEDEND}
	 * Channel.
	 *
	 * @param value the next value
	 */
	public default void _setTimeOfShedEnd(int value) {
		this.getTimeOfShedEndChannel().setNextValue(value);
	}	
}

