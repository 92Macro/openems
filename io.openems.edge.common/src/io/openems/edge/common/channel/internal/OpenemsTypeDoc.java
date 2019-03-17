package io.openems.edge.common.channel.internal;

import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.AccessMode;
import io.openems.edge.common.channel.BooleanDoc;
import io.openems.edge.common.channel.DoubleDoc;
import io.openems.edge.common.channel.FloatDoc;
import io.openems.edge.common.channel.IntegerDoc;
import io.openems.edge.common.channel.LongDoc;
import io.openems.edge.common.channel.ShortDoc;
import io.openems.edge.common.channel.StringDoc;
import io.openems.edge.common.channel.Unit;

public abstract class OpenemsTypeDoc<T> extends AbstractDoc<T> {

	public static OpenemsTypeDoc<?> of(OpenemsType type) {
		switch (type) {
		case BOOLEAN:
			return new BooleanDoc();
		case DOUBLE:
			return new DoubleDoc();
		case FLOAT:
			return new FloatDoc();
		case INTEGER:
			return new IntegerDoc();
		case LONG:
			return new LongDoc();
		case SHORT:
			return new ShortDoc();
		case STRING:
			return new StringDoc();
		}
		throw new IllegalArgumentException("OpenemsType [" + type + "] is unhandled. This should never happen.");
	}

	protected OpenemsTypeDoc(OpenemsType type) {
		super(type);
	}

	/**
	 * Sets the Access-Mode for the Channel.
	 * 
	 * <p>
	 * This is validated on construction of the Channel by
	 * {@link AbstractReadChannel}
	 * 
	 * @return myself
	 */
	public OpenemsTypeDoc<T> accessMode(AccessMode accessMode) {
		this.accessMode(accessMode);
		return this;
	}

	/*
	 * Unit
	 */
	private Unit unit = Unit.NONE;

	/**
	 * Unit. Default: none
	 * 
	 * @param unit the Unit
	 * @return myself
	 */
	public AbstractDoc<T> unit(Unit unit) {
		this.unit = unit;
		return this.self();
	}

	/**
	 * Gets the Unit.
	 * 
	 * @return the unit
	 */
	@Override
	public Unit getUnit() {
		return this.unit;
	}

}
