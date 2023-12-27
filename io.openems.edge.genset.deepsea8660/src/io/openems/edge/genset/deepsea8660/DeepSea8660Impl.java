package io.openems.edge.genset.deepsea8660;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.SignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC16WriteRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC6WriteRegisterTask;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.startstop.StartStop;
import io.openems.edge.common.startstop.StartStoppable;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.common.event.EdgeEventConstants;


@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "DeepSea8660", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)

@EventTopics({ //
	EdgeEventConstants.TOPIC_CYCLE_BEFORE_CONTROLLERS //
})

public class DeepSea8660Impl extends AbstractOpenemsModbusComponent implements StartStoppable, DeepSea8660, ModbusComponent, OpenemsComponent, EventHandler {

	@Reference
	private ConfigurationAdmin cm;
//	private StartStopConfig startStop;

	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	private Config config = null;
	private final Logger log = LoggerFactory.getLogger(DeepSea8660Impl.class);

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
						m(DeepSea8660.ChannelId.ACTIVE_POWER_LIMIT, new UnsignedWordElement(35109)), //
						new DummyRegisterElement(35110,35110), //
						m(DeepSea8660.ChannelId.REACTIVE_POWER_LIMIT, new UnsignedWordElement(35111)), //
						m(DeepSea8660.ChannelId.BUS_OR_MAINS_MODE, new UnsignedWordElement(35112))), //

				new FC3ReadRegistersTask(49408, Priority.HIGH, //
						m(DeepSea8660.ChannelId.REMOTE_CONTROL_SOURCE_1, new UnsignedWordElement(49408) //
								)),//	
				
				new FC3ReadRegistersTask(1059, Priority.HIGH, //
						m(DeepSea8660.ChannelId.GRID_FREQUENCY, new UnsignedWordElement(1059), SCALE_FACTOR_2 //
								)), //					
				new FC3ReadRegistersTask(1084, Priority.HIGH, //
						m(DeepSea8660.ChannelId.GRID_L1_POWER, new SignedDoublewordElement(1084), SCALE_FACTOR_MINUS_3), //
						m(DeepSea8660.ChannelId.GRID_L2_POWER, new SignedDoublewordElement(1086), SCALE_FACTOR_MINUS_3),		
						m(DeepSea8660.ChannelId.GRID_L3_POWER, new SignedDoublewordElement(1088), SCALE_FACTOR_MINUS_3)	
						
						),
				/*
				 * For Write: Write Outputs
				 */
				new FC16WriteRegistersTask(35109,
						m(DeepSea8660.ChannelId.ACTIVE_POWER_LIMIT, new UnsignedWordElement(35109))), //
				new FC16WriteRegistersTask(35111,
						m(DeepSea8660.ChannelId.REACTIVE_POWER_LIMIT, new UnsignedWordElement(35111))), //
				new FC16WriteRegistersTask(35112,
						m(DeepSea8660.ChannelId.BUS_OR_MAINS_MODE, new UnsignedWordElement(35112))), //
				new FC16WriteRegistersTask(49408,
						m(DeepSea8660.ChannelId.REMOTE_CONTROL_SOURCE_1, new UnsignedWordElement(49408))),
				
				/*
				 * For Write: Write Control Keys
				 */
				new FC16WriteRegistersTask(4104,
						m(DeepSea8660.ChannelId.SYSTEM_CONTROL_KEY, new UnsignedWordElement(4104)), //
						m(DeepSea8660.ChannelId.SYSTEM_CONTROL_KEY_COMPLIMENT, new UnsignedWordElement(4105))) //				
				);
	}
	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
	
		IntegerReadChannel GridPowerL1 = this.channel(DeepSea8660.ChannelId.GRID_L1_POWER);
		IntegerReadChannel GridPowerL2 = this.channel(DeepSea8660.ChannelId.GRID_L2_POWER);
		IntegerReadChannel GridPowerL3 = this.channel(DeepSea8660.ChannelId.GRID_L3_POWER);
		int GridTotalPower = 0;

		switch (event.getTopic()) {
		case EdgeEventConstants.TOPIC_CYCLE_BEFORE_CONTROLLERS:
			try {	
				if(GridPowerL1.value().asOptional().isPresent() && GridPowerL2.value().asOptional().isPresent() 
						&& GridPowerL3.value().asOptional().isPresent()) {
					
					GridTotalPower = GridPowerL1.value().get()+GridPowerL2.value().get()+GridPowerL3.value().get();
					if(GridTotalPower > 2000 || GridTotalPower < -2000) {//hard limit for max power
						GridTotalPower = 0; //reset to a value that is possible
					}
					this.channel(DeepSea8660.ChannelId.GRID_TOTAL_POWER).setNextValue(GridTotalPower);
				}		
			} 
			catch (Exception e) {
				this.logError(this.log, "Failed setting DeepSea8660 GRID_TOTAL_POWER: " + e.getMessage());
			}
			break;
		}
	}
	@Override
	public String debugLog() {
		return null/*"Hello World"*/;
	}
	

	@Override
	public void setStartStop(StartStop value) throws OpenemsNamedException {
		//var channel = (IntegerWriteChannel)this.channel(DeepSea8660.ChannelId.REMOTE_CONTROL_SOURCE_1);
		
		if (value == StartStop.START) {
			
			//channel.setNextWriteValue(1);			//set remote control to start
		}
		else if (value == StartStop.STOP) {
			
			//channel.setNextWriteValue(0);			//set remote control to stop
		}
		//if auto, do nothing
	}
}
