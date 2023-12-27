package io.openems.edge.genset.deepsea8660.controller;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Calendar;
import java.util.TimeZone;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.types.ChannelAddress;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.channel.WriteChannel;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.startstop.StartStop;
import io.openems.edge.controller.api.Controller;
import io.openems.edge.loadshedding.api.*;
import io.openems.edge.genset.deepsea8660.*;
import io.openems.edge.genset.deepsea8610.genset.*;


@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Controller.io.openems.edge.controller.genset.loadoffset", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class controllerImpl extends AbstractOpenemsComponent implements Controller, OpenemsComponent, DeepSea8660, DeepSeaGenset {

	@Reference
	protected ComponentManager componentManager;
	
	private Config config = null;
	private final Logger log = LoggerFactory.getLogger(controllerImpl.class);
	private Loadshedding loadshedding = null;
	private DeepSea8660 genSyncController = null;	
	private DeepSeaGenset genset1 = null;	
	private DeepSeaGenset genset2 = null;	
	private DeepSeaGenset genset3 = null;	
	private int loadPower = 0;
	
	private int prevGensetState = 0;	//previous system state: 0=Genset Off, 1=Genset On, 2=Genset in switching off process 
	private int counter = 0;		//stop mode has been set
	
	/**
	 * Stores the ChannelAddress of the ReadChannel.
	 */
	private ChannelAddress ActivePowerLimit = null;
	private ChannelAddress ReactivePowerLimit = null;
	private ChannelAddress BusOrMainsMode = null;	
	private ChannelAddress RemoteOnOff = null;	
	

	public controllerImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				Controller.ChannelId.values(), //
				DeepSea8660.ChannelId.values()
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsNamedException {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.config = config;
		this.loadshedding = this.componentManager.getComponent(this.config.loadshedding_id());
		this.genSyncController = this.componentManager.getComponent(this.config.syncpanel_id());
		this.genset1 = this.componentManager.getComponent(this.config.genset1_id());		
		this.genset2 = this.componentManager.getComponent(this.config.genset2_id());			
		this.genset3 = this.componentManager.getComponent(this.config.genset3_id());	
		
		this.ActivePowerLimit = ChannelAddress.fromString(config.syncpanel_id()+"/ActivePowerLimit");
		this.ReactivePowerLimit = ChannelAddress.fromString(config.syncpanel_id()+"/ReactivePowerLimit");
		this.BusOrMainsMode = ChannelAddress.fromString(config.syncpanel_id()+"/BusOrMainsMode");
		this.RemoteOnOff = ChannelAddress.fromString(config.syncpanel_id()+"/RemoteControlSource1");
	}

	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public void run() throws OpenemsNamedException {
		
		this.loadshedding = this.componentManager.getComponent(this.config.loadshedding_id());
		this.genSyncController = this.componentManager.getComponent(this.config.syncpanel_id());
		this.genset1 = this.componentManager.getComponent(this.config.genset1_id());		
		this.genset2 = this.componentManager.getComponent(this.config.genset2_id());			
		this.genset3 = this.componentManager.getComponent(this.config.genset3_id());			
		
		calcLoadPower();
		
        long currentTimeMillis = System.currentTimeMillis();
        
        // Convert to seconds
        long currentTimeSeconds = (currentTimeMillis / 1000) /*+ 7200*/; //GMT + 2?
        this.logInfo(this.log, "Current epoch time in seconds: " + currentTimeSeconds);

		int pSetpoint = 0;
		int RemoteControlSource1 = 0;
		int currentHour = getCurrentHour();
		
		try {

			/*this.logInfo(this.log, "ShedEnd: " + this.loadshedding.getTimeOfShedEnd().get() 
					+ ", Shed Start: " + this.loadshedding.getTimeOfShedStart().get());*/

			if(this.loadshedding.getTimeOfShedEnd().get() > currentTimeSeconds 
					&& this.loadshedding.getTimeOfShedStart().get() < currentTimeSeconds
						&& (currentHour < 6 || currentHour >= 18)) {//change from 6 to 18

				if(this.genSyncController.getGridFrequency().get() > 45000 
						&& this.genSyncController.getGridFrequency().get() < 55000) {

					if(this.genSyncController.getGridTotalPower().asOptional().isPresent()) {

						pSetpoint = (this.config.gen_percentage_id()*(this.loadPower))/this.config.gen_size_id();
						if (pSetpoint > 100 || pSetpoint < 0) {
							pSetpoint = 0;
							this.logInfo(this.log, "Genset Setpoint is out of bounds");
						}
						
						RemoteControlSource1 = 1;
						prevGensetState = 1;
						this.counter = 0;

					}	//if available
				}		//grid is active
			}			//load-shedding should be active
			else if(prevGensetState == 1){
				
				this.logInfo(this.log, "Genset to Stop mode!!!!!! ");
				 
				if(checkGensetFreqAvg() < 5) {		//wait for gennies to power off
					prevGensetState = 2;
				}
				
				setControlKey(35700, 29835);		//set genset to STOP mode
			}
			else if(prevGensetState == 2) {
				
				this.logInfo(this.log, "Genset back to AUTO mode!!!!!! ");
				
				setControlKey(35701, 29834);		//set genset to AUTO mode 	
				this.counter++;
				
				if(this.counter>10) {
					prevGensetState = 0;
					this.counter = 0;
				}
			}
			else {
				prevGensetState = 0;
				this.counter = 0;
			}

			setOutput(pSetpoint,RemoteControlSource1);

			this.logInfo(this.log, "Gen Remote Ctrl: " + RemoteControlSource1 + ", pSetpoint: " + pSetpoint);
		}
		catch (Exception e) {
			this.logError(this.log, "Failed setting DeepSea8660 GRID_TOTAL_POWER: " + e.getMessage());
		}

	}
	
	private void calcLoadPower() {

		try {
			this.loadPower = this.genSyncController.getGridTotalPower().get() 
					+ this.genset1.getGensetTotalPower().get() 
					+ this.genset2.getGensetTotalPower().get() 
					+ this.genset3.getGensetTotalPower().get();
		}
		catch (Exception e) {
			this.logError(this.log, "Failed Calculating LOAD_TOTAL_POWER: " + e.getMessage());
			this.loadPower = 0;
		}
		
		return;
	}
	
	private void setOutput(int power, int remoteOnOff) throws IllegalArgumentException, OpenemsNamedException {
		
		WriteChannel<Integer> activePowerChannel = this.componentManager.getChannel(this.ActivePowerLimit);
		WriteChannel<Integer> reactivePowerChannel = this.componentManager.getChannel(this.ReactivePowerLimit);
		WriteChannel<Integer> busOrMainsModeChannel = this.componentManager.getChannel(this.BusOrMainsMode);		
		WriteChannel<Integer> remoteOnOffChannel = this.componentManager.getChannel(this.RemoteOnOff);

		activePowerChannel.setNextWriteValue(power);
		reactivePowerChannel.setNextWriteValue(0);
		busOrMainsModeChannel.setNextWriteValue(0);
		remoteOnOffChannel.setNextWriteValue(remoteOnOff);
	}
	
	private void setControlKey(int key, int keyCompliment) throws IllegalArgumentException, OpenemsNamedException {
		
		WriteChannel<Integer> systemControlKeyChannel = this.componentManager.getChannel(ChannelAddress.fromString(config.syncpanel_id()+"/SystemControlKey"));
		WriteChannel<Integer> systemControlKeyComplimentChannel = this.componentManager.getChannel(ChannelAddress.fromString(config.syncpanel_id()+"/SystemControlKeyCompliment"));

		systemControlKeyChannel.setNextWriteValue(key);
		systemControlKeyComplimentChannel.setNextWriteValue(keyCompliment);
	}
	
	private int checkGensetFreqAvg() throws IllegalArgumentException, OpenemsNamedException {
		int gensetFreqAvg = 0;
		gensetFreqAvg = this.genset1.getGensetFrequency().get() + this.genset2.getGensetFrequency().get() + this.genset3.getGensetFrequency().get();

		gensetFreqAvg = gensetFreqAvg/3000;
		
		return gensetFreqAvg;
	}
	
	private int getCurrentHour() {
        // Create a Calendar instance and set the desired time zone (e.g., UTC+2)
        Calendar now = Calendar.getInstance(TimeZone.getTimeZone("GMT+2"));

        // Get the current hour in the specified time zone
        int hour = now.get(Calendar.HOUR_OF_DAY);
        
        //this.logInfo(this.log, "Current hour of day: " + hour);

        return hour;
	}
}
