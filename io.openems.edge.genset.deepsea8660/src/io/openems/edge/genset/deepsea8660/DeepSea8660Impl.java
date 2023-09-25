package io.openems.edge.genset.deepsea8660;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_3;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.metatype.annotations.Designate;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.FloatDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC16WriteRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.startstop.StartStop;
import io.openems.edge.common.startstop.StartStoppable;
import io.openems.edge.common.taskmanager.Priority;



@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "DeepSea8660", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)

public class DeepSea8660Impl extends AbstractOpenemsModbusComponent implements StartStoppable, DeepSea8660, ModbusComponent, OpenemsComponent {

	@Reference
	private ConfigurationAdmin cm;
//	private StartStopConfig startStop;

	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	private Config config = null;

	public DeepSea8660Impl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				DeepSea8660.ChannelId.values(), //
				StartStoppable.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		if(super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId(), this.cm, "Modbus",
				config.modbus_id())) {
			return;
		}
		this.config = config;
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() throws OpenemsException {
		// TODO Test ModbusProtocol
		return new ModbusProtocol(this, //
				new FC3ReadRegistersTask(35109, Priority.HIGH, //
						m(DeepSea8660.ChannelId.ACTIVE_POWER_LIMIT, new UnsignedWordElement(35109), //
								SCALE_FACTOR_3), //
						new DummyRegisterElement(35110), //
						m(DeepSea8660.ChannelId.REACTIVE_POWER_LIMIT, new UnsignedWordElement(35111), //
								SCALE_FACTOR_3), //
						m(DeepSea8660.ChannelId.BUS_OR_MAINS_MODE, new UnsignedWordElement(35112))), //

				new FC3ReadRegistersTask(49408, Priority.HIGH, //
						m(DeepSea8660.ChannelId.REMOTE_CONTROL_SOURCE_1, new UnsignedWordElement(49408) //
								)), //								
				/*
				 * For Write: Write Outputs
				 */
				new FC16WriteRegistersTask(35109,
						m(DeepSea8660.ChannelId.ACTIVE_POWER_LIMIT, new UnsignedWordElement(35109))), //
				new FC16WriteRegistersTask(35110,
						m(DeepSea8660.ChannelId.REACTIVE_POWER_LIMIT, new UnsignedWordElement(35110))), //
				new FC16WriteRegistersTask(35112,
						m(DeepSea8660.ChannelId.BUS_OR_MAINS_MODE, new UnsignedWordElement(35112))), //
				new FC16WriteRegistersTask(49408,
						m(DeepSea8660.ChannelId.REMOTE_CONTROL_SOURCE_1, new UnsignedWordElement(49408)))) //
						;
	}

	@Override
	public String debugLog() {
		return null/*"Hello World"*/;
	}
	

	@Override
	public void setStartStop(StartStop value) throws OpenemsNamedException {
		var channel = (IntegerWriteChannel)this.channel(DeepSea8660.ChannelId.REMOTE_CONTROL_SOURCE_1);
		
		if (value == StartStop.START) {
			
			channel.setNextWriteValue(1);			//set remote control to start
		}
		else if (value == StartStop.STOP) {
			
			channel.setNextWriteValue(0);			//set remote control to stop
		}
	}
}
