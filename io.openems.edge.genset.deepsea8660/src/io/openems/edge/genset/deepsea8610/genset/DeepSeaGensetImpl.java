package io.openems.edge.genset.deepsea8610.genset;

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

import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.SignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC16WriteRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.startstop.StartStop;
import io.openems.edge.common.startstop.StartStoppable;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.genset.deepsea8660.DeepSea8660;
import io.openems.edge.genset.deepsea8660.DeepSea8660Impl;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "DeepSeaGenset", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)

@EventTopics({ //
	EdgeEventConstants.TOPIC_CYCLE_BEFORE_CONTROLLERS //
})

public class DeepSeaGensetImpl extends AbstractOpenemsModbusComponent implements DeepSeaGenset, ModbusComponent, OpenemsComponent,  EventHandler {

	@Reference
	private ConfigurationAdmin cm;
//	private StartStopConfig startStop;

	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	private Config config = null;
	private final Logger log = LoggerFactory.getLogger(DeepSea8660Impl.class);

	public DeepSeaGensetImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				DeepSeaGenset.ChannelId.values()
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
		return new ModbusProtocol(this, //
			
				new FC3ReadRegistersTask(1031, Priority.HIGH, //
						m(DeepSeaGenset.ChannelId.GENSET_FREQUENCY, new UnsignedWordElement(1031), SCALE_FACTOR_2 )), //	
				
				new FC3ReadRegistersTask(1032, Priority.LOW, //
						m(DeepSeaGenset.ChannelId.GENSET_L1_N_VOLTAGE, new UnsignedDoublewordElement(1032), SCALE_FACTOR_MINUS_1), //
						m(DeepSeaGenset.ChannelId.GENSET_L2_N_VOLTAGE, new UnsignedDoublewordElement(1034), SCALE_FACTOR_MINUS_1),		
						m(DeepSeaGenset.ChannelId.GENSET_L3_N_VOLTAGE, new UnsignedDoublewordElement(1036), SCALE_FACTOR_MINUS_1)), //	
				
				new FC3ReadRegistersTask(1052, Priority.HIGH, //
						m(DeepSeaGenset.ChannelId.GENSET_L1_POWER, new SignedDoublewordElement(1052), SCALE_FACTOR_MINUS_3), //
						m(DeepSeaGenset.ChannelId.GENSET_L2_POWER, new SignedDoublewordElement(1054), SCALE_FACTOR_MINUS_3),		
						m(DeepSeaGenset.ChannelId.GENSET_L3_POWER, new SignedDoublewordElement(1056), SCALE_FACTOR_MINUS_3))/*,
						
				new FC3ReadRegistersTask(1356, Priority.HIGH, //
						m(DeepSeaGenset.ChannelId.GENSET_TOTAL_POWER, new SignedDoublewordElement(1356), SCALE_FACTOR_MINUS_3)) //	*/				
				);
	}
	
	@Override
	public String debugLog() {
		return null;
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		
		IntegerReadChannel GenPowerL1 = this.channel(DeepSeaGenset.ChannelId.GENSET_L1_POWER);
		IntegerReadChannel GenPowerL2 = this.channel(DeepSeaGenset.ChannelId.GENSET_L2_POWER);
		IntegerReadChannel GenPowerL3 = this.channel(DeepSeaGenset.ChannelId.GENSET_L3_POWER);
		int GenTotalPower = 0;

		switch (event.getTopic()) {
		case EdgeEventConstants.TOPIC_CYCLE_BEFORE_CONTROLLERS:
			try {	
				if(GenPowerL1.value().asOptional().isPresent() && GenPowerL2.value().asOptional().isPresent() 
						&& GenPowerL3.value().asOptional().isPresent()) {
					
					GenTotalPower = GenPowerL1.value().get()+GenPowerL2.value().get()+GenPowerL3.value().get();
					if(GenTotalPower > 2000 || GenTotalPower < -2000) {//hard limit for max power
						GenTotalPower = 0; //reset to a value that is possible
					}
					this.channel(DeepSeaGenset.ChannelId.GENSET_TOTAL_POWER).setNextValue(GenTotalPower);
				}	
			} 
			catch (Exception e) {
				this.logError(this.log, "Failed setting DeepSea8660 GRID_TOTAL_POWER: " + e.getMessage());
				this.channel(DeepSeaGenset.ChannelId.GENSET_TOTAL_POWER).setNextValue(GenTotalPower);
			}
			break;
		}
	}
}
