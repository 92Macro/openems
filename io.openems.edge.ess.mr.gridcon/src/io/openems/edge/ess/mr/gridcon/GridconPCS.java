package io.openems.edge.ess.mr.gridcon;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

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
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.ChannelAddress;
import io.openems.edge.battery.api.Battery;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ElementToChannelConverter;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.BitsWordElement;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.FloatDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.element.WordOrder;
import io.openems.edge.bridge.modbus.api.task.FC16WriteRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.channel.BooleanReadChannel;
import io.openems.edge.common.channel.BooleanWriteChannel;
import io.openems.edge.common.channel.FloatReadChannel;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.StateChannel;
import io.openems.edge.common.channel.WriteChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.common.modbusslave.ModbusSlaveNatureTable;
import io.openems.edge.common.modbusslave.ModbusSlaveTable;
import io.openems.edge.common.sum.GridMode;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.ess.api.SymmetricEss;
import io.openems.edge.ess.mr.gridcon.enums.CCUState;
import io.openems.edge.ess.mr.gridcon.enums.ErrorCodeChannelId;
import io.openems.edge.ess.mr.gridcon.enums.ErrorCodeChannelId1;
import io.openems.edge.ess.mr.gridcon.enums.ErrorDoc;
import io.openems.edge.ess.mr.gridcon.enums.GridConChannelId;
import io.openems.edge.ess.mr.gridcon.enums.InverterCount;
import io.openems.edge.ess.mr.gridcon.enums.PControlMode;
import io.openems.edge.ess.mr.gridcon.enums.StateMachine;
import io.openems.edge.ess.power.api.Constraint;
import io.openems.edge.ess.power.api.Phase;
import io.openems.edge.ess.power.api.Power;
import io.openems.edge.ess.power.api.Pwr;
import io.openems.edge.ess.power.api.Relationship;
import io.openems.edge.meter.api.SymmetricMeter;

@Designate(ocd = Config.class, factory = true)
@Component( //
		name = "Ess.MR.Gridcon", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE, //
		property = EventConstants.EVENT_TOPIC + "=" + EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE //
)
public class GridconPCS extends AbstractOpenemsModbusComponent
		implements ManagedSymmetricEss, SymmetricEss, OpenemsComponent, EventHandler, ModbusSlave {

	public static final int MAX_POWER_PER_INVERTER = 41_900; // experimentally measured

	private static final float DC_LINK_VOLTAGE_SETPOINT = 800f;
	private static final float DC_LINK_VOLTAGE_TOLERANCE_VOLT = 20;
	private static final int GRIDCON_SWITCH_OFF_TIME_SECONDS = 15;
	private static final int GRIDCON_BOOT_TIME_SECONDS = 30;
	private static final float MAX_CHARGE_W = 86 * 1000;
	private static final float MAX_DISCHARGE_W = 86 * 1000;

	private final Logger log = LoggerFactory.getLogger(GridconPCS.class);

	private Config config;
	private Map<Integer, io.openems.edge.common.channel.ChannelId> errorChannelIds = null;
	private LocalDateTime timestampMrGridconWasSwitchedOff;

	@Reference
	private Power power;

	@Reference
	protected ConfigurationAdmin cm;

	@Reference
	protected ComponentManager componentManager;

	private LocalDateTime lastTimeAcknowledgeCommandoWasSent;
	private long ACKNOWLEDGE_TIME_SECONDS = 5;
	private LocalDateTime offGridDetected = null;
	private int DO_NOTHING_IN_OFFGRID_FOR_THE_FIRST_SECONDS = 5;

	public GridconPCS() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				SymmetricEss.ChannelId.values(), //
				ManagedSymmetricEss.ChannelId.values(), //
				ErrorCodeChannelId.values(), //
				ErrorCodeChannelId1.values(), //
				GridConChannelId.values() //
		);
		fillErrorChannelMap();
	}

	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	@Activate
	void activate(ComponentContext context, Config config) throws OpenemsNamedException {
		this.config = config;

		// Calculate max apparent power from number of inverters
		this.getMaxApparentPower().setNextValue(config.inverterCount().getMaxApparentPower());

		// Call parent activate()
		super.activate(context, config.id(), config.enabled(), config.unit_id(), this.cm, "Modbus", config.modbus_id());
	}

	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	private void fillErrorChannelMap() {
		// TODO move to static map in Enum
		errorChannelIds = new HashMap<>();
		for (io.openems.edge.common.channel.ChannelId id : ErrorCodeChannelId.values()) {
			errorChannelIds.put(((ErrorDoc) id.doc()).getCode(), id);
		}
		for (io.openems.edge.common.channel.ChannelId id : ErrorCodeChannelId1.values()) {
			errorChannelIds.put(((ErrorDoc) id.doc()).getCode(), id);
		}
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		switch (event.getTopic()) {
		case EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE:
			try {
				// prepare calculated Channels
				GridMode gridMode = this.evaluateGridMode();
				this.handleBatteryData();
				this.calculateSoc();

				// start state-machine handling
				this.handleStateMachine();

				this.channel(GridConChannelId.STATE_CYCLE_ERROR).setNextValue(false);
			} catch (IllegalArgumentException | OpenemsNamedException e) {
				this.channel(GridConChannelId.STATE_CYCLE_ERROR).setNextValue(true);
				this.logError(this.log, "State-Cycle Error: " + e.getMessage());
			}
			break;
		}
	}

	/**
	 * Evaluates the Grid-Mode and sets the GRID_MODE channel accordingly.
	 * 
	 * @return
	 * 
	 * @throws OpenemsNamedException
	 * @throws IllegalArgumentException
	 */
	private GridMode evaluateGridMode() throws IllegalArgumentException, OpenemsNamedException {
		GridMode gridMode = GridMode.UNDEFINED;
		try {
			BooleanReadChannel inputNAProtection1 = this.componentManager
					.getChannel(ChannelAddress.fromString(this.config.inputNAProtection1()));
			BooleanReadChannel inputNAProtection2 = this.componentManager
					.getChannel(ChannelAddress.fromString(this.config.inputNAProtection2()));

			Optional<Boolean> isInputNAProtection1 = inputNAProtection1.value().asOptional();
			Optional<Boolean> isInputNAProtection2 = inputNAProtection2.value().asOptional();

			if (!isInputNAProtection1.isPresent() || !isInputNAProtection2.isPresent()) {
				gridMode = GridMode.UNDEFINED;
			} else {
				if (isInputNAProtection1.get() && isInputNAProtection2.get()) {
					gridMode = GridMode.ON_GRID;
				} else {
					gridMode = GridMode.OFF_GRID;
				}
			}

		} finally {
			this.getGridMode().setNextValue(gridMode);
		}
		return gridMode;
	}

	/**
	 * Handles Battery data, i.e. setting allowed charge/discharge power.
	 */
	private void handleBatteryData() {
		int allowedCharge = 0;
		int allowedDischarge = 0;
		for (Battery battery : this.getBatteries()) {
			allowedCharge += battery.getVoltage().value().orElse(0) * battery.getChargeMaxCurrent().value().orElse(0)
					* -1;
			allowedDischarge += battery.getVoltage().value().orElse(0)
					* battery.getDischargeMaxCurrent().value().orElse(0);
		}
		this.getAllowedCharge().setNextValue(allowedCharge);
		this.getAllowedDischarge().setNextValue(allowedDischarge);
	}

	/**
	 * Calculates the State-of-charge of all Batteries; if all batteries are
	 * available. Otherwise sets UNDEFINED.
	 */
	private void calculateSoc() {
		float sumTotalCapacity = 0;
		float sumCurrentCapacity = 0;
		for (Battery b : this.getBatteries()) {
			Optional<Integer> totalCapacityOpt = b.getCapacity().value().asOptional();
			Optional<Integer> socOpt = b.getSoc().value().asOptional();
			if (!totalCapacityOpt.isPresent() || !socOpt.isPresent()) {
				// if at least one Battery has no valid value -> set UNDEFINED
				this.getSoc().setNextValue(null);
				return;
			}
			int totalCapacity = totalCapacityOpt.get();
			int soc = socOpt.get();
			sumTotalCapacity += totalCapacity;
			sumCurrentCapacity += totalCapacity * soc / 100.0;
		}
		int soc = Math.round(sumCurrentCapacity * 100 / sumTotalCapacity);
		this.getSoc().setNextValue(soc);
	}

	private void handleStateMachine() throws IllegalArgumentException, OpenemsNamedException {
		switch (this.getStateMachine()) {
		case IDLE:
			// TODO
			break;

		case ONGRID_NORMAL_OPERATION:
			this.handleOnGridNormalOperation();
			break;

		case UNDEFINED:
			break;
		}
	}

	/**
	 * Handles normal operation in On-Grid.
	 * 
	 * @throws OpenemsNamedException
	 * @throws IllegalArgumentException
	 */
	private void handleOnGridNormalOperation() throws IllegalArgumentException, OpenemsNamedException {
		new CommandControlRegisters() //
				.syncApproval(true) //
				.shortCircuitHandling(true) //
				.modeSelection(CommandControlRegisters.Mode.CURRENT_CONTROL) //
				.parameterSet1(true) //
				.parameterU0(1f) //
				.parameterF0(1.035f) //
				.writeToChannels(this);
		new CcuControlParameters() //
				.pControlMode(PControlMode.ACTIVE_POWER_CONTROL) //
				.qLimit(1f) //
				.writeToChannels(this);
		InverterCount inverterCount = this.config.inverterCount();
		switch (inverterCount) {
		case ONE:
			new IpuInverterControl() //
					.pMaxCharge(inverterCount.getMaxApparentPower() * -1) //
					.pMaxDischarge(inverterCount.getMaxApparentPower()) //
					.writeToChannels(this, IpuInverterControl.Inverter.ONE);
			new IpuInverterControl() //
					.writeToChannels(this, IpuInverterControl.Inverter.TWO) //
					.writeToChannels(this, IpuInverterControl.Inverter.THREE);

		case TWO:
			new IpuInverterControl() //
					.pMaxCharge(inverterCount.getMaxApparentPower() * -1) //
					.pMaxDischarge(inverterCount.getMaxApparentPower()) //
					.writeToChannels(this, IpuInverterControl.Inverter.ONE) //
					.writeToChannels(this, IpuInverterControl.Inverter.TWO); //
			new IpuInverterControl() //
					.writeToChannels(this, IpuInverterControl.Inverter.THREE);

		case THREE:
			new IpuInverterControl() //
					.pMaxCharge(inverterCount.getMaxApparentPower() * -1) //
					.pMaxDischarge(inverterCount.getMaxApparentPower()) //
					.writeToChannels(this, IpuInverterControl.Inverter.ONE) //
					.writeToChannels(this, IpuInverterControl.Inverter.TWO) //
					.writeToChannels(this, IpuInverterControl.Inverter.THREE);
		}
		new DcdcControl() //
				.dcVoltageSetpoint(GridconPCS.DC_LINK_VOLTAGE_SETPOINT) //
				.stringControlMode(this.componentManager, this.config) //
				.writeToChannels(this);
	}

	/**
	 * Evaluates the current StateMachine-State.
	 * 
	 * @return the StateMachine
	 */
	private StateMachine getStateMachine() {
		GridMode gridMode = this.getGridMode().getNextValue().asEnum();
		StateChannel errorChannel = this.getErrorChannel();

		// TODO IDLE

		if (gridMode == GridMode.ON_GRID && errorChannel == null) {
			return StateMachine.ONGRID_NORMAL_OPERATION;
		}

		// TODO weitere States, z. B. Going_ON_GRID, OFF_GRID,...

		return StateMachine.UNDEFINED;
	}

	private void prepareGeneralCommands() throws OpenemsNamedException {

		writeValueToChannel(GridConChannelId.COMMAND_ERROR_CODE_FEEDBACK, 0);
		writeValueToChannel(GridConChannelId.COMMAND_CONTROL_PARAMETER_Q_REF, 0);
		writeValueToChannel(GridConChannelId.COMMAND_CONTROL_PARAMETER_P_REF, 0);
		/**
		 * Always write values for frequency and voltage to gridcon, because in case of
		 * blackstart mode if we write '0' to gridcon the systems tries to regulate
		 * frequency and voltage to zero which would be bad for Mr. Gridcon's health
		 */
		writeValueToChannel(GridConChannelId.COMMAND_CONTROL_PARAMETER_U0, 1.0f);
		writeValueToChannel(GridConChannelId.COMMAND_CONTROL_PARAMETER_F0, 1.035f);
		writeDateAndTime();

		writeCCUControlParameters(PControlMode.ACTIVE_POWER_CONTROL);
		writeIPUParameters(1f, 1f, 1f, MAX_DISCHARGE_W, MAX_DISCHARGE_W, MAX_DISCHARGE_W, MAX_CHARGE_W, MAX_CHARGE_W,
				MAX_CHARGE_W, getWeightingMode());
	}

	/**
	 * Calculates the weighting of the Batteries.
	 * 
	 * @return the weighting
	 * @throws OpenemsNamedException on error
	 */
	private float getWeightingMode() throws OpenemsNamedException {
		int weightingMode = 0;

		// Depends on number of battery strings!!!
		// battA = 1 (2^0)
		// battB = 8 (2^3)
		// battC = 64 (2^6)

		if (this.config.batteryStringA_id() != null && this.config.batteryStringA_id().length() > 0) {
			Battery batteryStringA = this.componentManager.getComponent(this.config.batteryStringA_id());
			if (batteryStringA != null) {
				weightingMode = weightingMode + 1;
			}
		}
		if (this.config.batteryStringB_id() != null && this.config.batteryStringB_id().length() > 0) {
			Battery batteryStringB = this.componentManager.getComponent(this.config.batteryStringB_id());
			if (batteryStringB != null) {
				weightingMode = weightingMode + 8;
			}
		}
		if (this.config.batteryStringC_id() != null && this.config.batteryStringC_id().length() > 0) {
			Battery batteryStringC = this.componentManager.getComponent(this.config.batteryStringC_id());
			if (batteryStringC != null) {
				weightingMode = weightingMode + 64;
			}
		}

		// weighting is never allowed to be '0'
		if (weightingMode == 0) {
			throw new OpenemsException("Calculated weight of '0' -> not allowed!");
		}

		return weightingMode;
	}

	private void handleOnGridState() throws IllegalArgumentException, OpenemsNamedException {
		offGridDetected = null;
		System.out.println(" ------ Currently set error channels ------- ");
		for (io.openems.edge.common.channel.ChannelId id : errorChannelIds.values()) {
			@SuppressWarnings("unchecked")
			Optional<Boolean> val = (Optional<Boolean>) this.channel(id).value().asOptional();
			if (val.isPresent() && val.get()) {
				System.out.println(this.channel(id).address().getChannelId() + " is present");
			}
		}
		// Always set OutputSyncDeviceBridge OFF in On-Grid state
		this.setOutputSyncDeviceBridge(false);

		// TODO check
		// Just temporarily, because sometime gridcon reduces the link voltage, i.e.
		// there is no function any longer but also no errors

		// a hardware restart has been executed,
		if (timestampMrGridconWasSwitchedOff != null) {
			log.info("timestampMrGridconWasSwitchedOff is set: " + timestampMrGridconWasSwitchedOff.toString());
			if ( //
			LocalDateTime.now().isAfter(timestampMrGridconWasSwitchedOff.plusSeconds(GRIDCON_SWITCH_OFF_TIME_SECONDS))
					&& //
					LocalDateTime.now().isBefore(timestampMrGridconWasSwitchedOff
							.plusSeconds(GRIDCON_SWITCH_OFF_TIME_SECONDS + GRIDCON_BOOT_TIME_SECONDS)) //
			) {
				try {
					log.info("try to write to channel hardware reset, set it to 'false'");
					// after 15 seconds switch Mr. Gridcon on again!
					BooleanWriteChannel channelHardReset = this.componentManager
							.getChannel(ChannelAddress.fromString(this.config.outputMRHardReset()));
					channelHardReset.setNextWriteValue(false);
					resetErrorChannels();
				} catch (IllegalArgumentException | OpenemsNamedException e) {
					log.error("Problem occurred while deactivating hardware switch!");
					e.printStackTrace();
				}

			} else if (LocalDateTime.now().isAfter(timestampMrGridconWasSwitchedOff
					.plusSeconds(GRIDCON_SWITCH_OFF_TIME_SECONDS + GRIDCON_BOOT_TIME_SECONDS))) {
				timestampMrGridconWasSwitchedOff = null;
			}
			return;
		}

		switch (getCurrentState()) {
		case DERATING_HARMONICS:
			break;
		case DERATING_POWER:
			break;
		case ERROR:
			doErrorHandling();
			break;
		case IDLE:
			startSystem();
			break;
		case OVERLOAD:
			break;
		case PAUSE:
			break;
		case PRECHARGE:
			break;
		case READY:
			break;
		case RUN:
			doRunHandling();
			break;
		case SHORT_CIRCUIT_DETECTED:
			break;
		case SIA_ACTIVE:
			break;
		case STOP_PRECHARGE:
			break;
		case UNDEFINED:
			break;
		case VOLTAGE_RAMPING_UP:
			break;
		}

		resetErrorCodes();
	}

	private void resetErrorChannels() {
		for (io.openems.edge.common.channel.ChannelId id : errorChannelIds.values()) {
			this.channel(id).setNextValue(false);
		}
	}

	private void resetErrorCodes() {
		IntegerReadChannel errorCodeChannel = this.channel(GridConChannelId.CCU_ERROR_CODE);
		Optional<Integer> errorCodeOpt = errorCodeChannel.value().asOptional();
		log.debug("in resetErrorCodes: => Errorcode: " + errorCodeOpt);
		if (errorCodeOpt.isPresent() && errorCodeOpt.get() != 0) {
			writeValueToChannel(GridConChannelId.COMMAND_ERROR_CODE_FEEDBACK, errorCodeOpt.get());
		}
	}

	private void doRunHandling() throws OpenemsNamedException {
		FloatReadChannel fcr = this.channel(GridConChannelId.DCDC_STATUS_DC_LINK_POSITIVE_VOLTAGE);
		Optional<Float> linkVoltageOpt = fcr.value().asOptional();
		if (!linkVoltageOpt.isPresent()) {
			return;
		}

		float linkVoltage = linkVoltageOpt.get();
		float difference = Math.abs(GridconPCS.DC_LINK_VOLTAGE_SETPOINT - linkVoltage);

		if (difference > GridconPCS.DC_LINK_VOLTAGE_TOLERANCE_VOLT) {
			doHardRestart();
			return;
		}
		resetErrorChannels(); // if any error channels has been set, unset them because in here there are no
		// errors present ==> TODO EBEN NICHT!!! fall aufgetreten dass state RUN war
		// aber ein fehler in der queue und das system nicht angelaufen ist....

		boolean disableIpu1 = false;
		boolean disableIpu2 = true;
		boolean disableIpu3 = true;
		boolean disableIpu4 = true;

		switch (this.config.inverterCount()) {
		case ONE:
			disableIpu2 = false;
			break;
		case TWO:
			disableIpu2 = false;
			disableIpu3 = false;
			break;
		case THREE:
			disableIpu2 = false;
			disableIpu3 = false;
			disableIpu4 = false;
			break;
		}

		// send play command
		this.setNextWriteValueToBooleanWriteChannel(GridConChannelId.COMMAND_CONTROL_WORD_PLAY, true); // TODO just for
																										// testing
																										// purposes,
																										// because play
																										// is only
																										// necessary at
																										// startup

		this.setNextWriteValueToBooleanWriteChannel(GridConChannelId.COMMAND_CONTROL_WORD_DISABLE_IPU_1, disableIpu1);
		this.setNextWriteValueToBooleanWriteChannel(GridConChannelId.COMMAND_CONTROL_WORD_DISABLE_IPU_2, disableIpu2);
		this.setNextWriteValueToBooleanWriteChannel(GridConChannelId.COMMAND_CONTROL_WORD_DISABLE_IPU_3, disableIpu3);
		this.setNextWriteValueToBooleanWriteChannel(GridConChannelId.COMMAND_CONTROL_WORD_DISABLE_IPU_4, disableIpu4);
	}

	private void setNextWriteValueToBooleanWriteChannel(GridConChannelId id, boolean b) throws OpenemsNamedException {
		((BooleanWriteChannel) this.channel(id)).setNextWriteValue(true);

	}

	private void writeCCUControlParameters(PControlMode mode) {
		writeValueToChannel(GridConChannelId.CONTROL_PARAMETER_U_Q_DROOP_MAIN, 0f);
		writeValueToChannel(GridConChannelId.CONTROL_PARAMETER_U_Q_DROOP_T1_MAIN, 0f);
		writeValueToChannel(GridConChannelId.CONTROL_PARAMETER_F_P_DROOP_MAIN, 0f);
		writeValueToChannel(GridConChannelId.CONTROL_PARAMETER_F_P_DROOP_T1_MAIN, 0f);
		writeValueToChannel(GridConChannelId.CONTROL_PARAMETER_Q_U_DROOP_MAIN, 0f);
		writeValueToChannel(GridConChannelId.CONTROL_PARAMETER_Q_U_DEAD_BAND, 0f);
		writeValueToChannel(GridConChannelId.CONTROL_PARAMETER_Q_LIMIT, 1f); // 0 -> limits Q to zero, 1 -> to max Q
		writeValueToChannel(GridConChannelId.CONTROL_PARAMETER_P_F_DROOP_MAIN, 0f);
		writeValueToChannel(GridConChannelId.CONTROL_PARAMETER_P_F_DEAD_BAND, 0f);
		writeValueToChannel(GridConChannelId.CONTROL_PARAMETER_P_U_DROOP, 0f);
		writeValueToChannel(GridConChannelId.CONTROL_PARAMETER_P_U_DEAD_BAND, 0f);
		writeValueToChannel(GridConChannelId.CONTROL_PARAMETER_P_U_MAX_CHARGE, 0f);
		writeValueToChannel(GridConChannelId.CONTROL_PARAMETER_P_U_MAX_DISCHARGE, 0f);
		writeValueToChannel(GridConChannelId.CONTROL_PARAMETER_P_CONTROL_LIM_TWO, 0f);
		writeValueToChannel(GridConChannelId.CONTROL_PARAMETER_P_CONTROL_LIM_ONE, 0f);
		// the only relevant parameter is 'P Control Mode' which should be set to
		// 'Active power control' in case of on grid usage
		writeValueToChannel(GridConChannelId.CONTROL_PARAMETER_P_CONTROL_MODE, mode.getFloatValue()); //
	}

	/**
	 * This writes the current time into the necessary channel for the
	 * communicationprotocol with the gridcon.
	 */
	private void writeDateAndTime() {
		LocalDateTime time = LocalDateTime.now();
		byte dayOfWeek = (byte) time.getDayOfWeek().ordinal();
		byte day = (byte) time.getDayOfMonth();
		byte month = (byte) time.getMonth().getValue();
		byte year = (byte) (time.getYear() - 2000); // 0 == year 2000 in the protocol

		Integer dateInteger = convertToInteger(BitSet.valueOf(new byte[] { day, dayOfWeek, year, month }));

		byte seconds = (byte) time.getSecond();
		byte minutes = (byte) time.getMinute();
		byte hours = (byte) time.getHour();

		// second byte is unused
		Integer timeInteger = convertToInteger(BitSet.valueOf(new byte[] { seconds, 0, hours, minutes }));

		writeValueToChannel(GridConChannelId.COMMAND_TIME_SYNC_DATE, dateInteger);
		writeValueToChannel(GridConChannelId.COMMAND_TIME_SYNC_TIME, timeInteger);

	}

	/**
	 * This turns on the system by enabling ALL IPUs.
	 * 
	 * @throws OpenemsException
	 */
	private void startSystem() throws OpenemsNamedException {
		log.info("Try to start system");
		/*
		 * Coming from state idle first write 800V to IPU4 voltage setpoint, set "73" to
		 * DCDC String Control Mode of IPU4 and "1" to Weight String A, B, C ==> i.e.
		 * all 3 IPUs are weighted equally write -86000 to Pmax discharge Iref String A,
		 * B, C, write 86000 to Pmax Charge DCDC Str Mode of IPU 1, 2, 3 set P Control
		 * mode to "Act Pow Ctrl" (hex 4000 = mode power limiter, 0 = disabled, hex 3F80
		 * = active power control) and Mode Sel to "Current Control" s--> ee pic
		 * start0.png in doc folder
		 * 
		 * enable "Sync Approval" and "Ena IPU 4" and PLAY command -> system should
		 * change state to "RUN" --> see pic start1.png
		 * 
		 * after that enable IPU 1, 2, 3, if they have reached state "RUN" (=14) power
		 * can be set (from 0..1 (1 = max system power = 125 kW) , i.e. 0,05 is equal to
		 * 6.250 W same for reactive power see pic start2.png
		 * 
		 * "Normal mode" is reached now
		 */

		// enable "Sync Approval" and "Ena IPU 4, 3, 2, 1" and PLAY command -> system
		// should change state to "RUN"

		this.setNextWriteValueToBooleanWriteChannel(GridConChannelId.COMMAND_CONTROL_WORD_PLAY, true);
		this.setNextWriteValueToBooleanWriteChannel(GridConChannelId.COMMAND_CONTROL_WORD_SYNC_APPROVAL, true);
		this.setNextWriteValueToBooleanWriteChannel(GridConChannelId.COMMAND_CONTROL_WORD_BLACKSTART_APPROVAL, false);
		this.setNextWriteValueToBooleanWriteChannel(GridConChannelId.COMMAND_CONTROL_WORD_MODE_SELECTION, true);
		this.setNextWriteValueToBooleanWriteChannel(
				GridConChannelId.COMMAND_CONTROL_WORD_ACTIVATE_SHORT_CIRCUIT_HANDLING, true);
		this.setNextWriteValueToBooleanWriteChannel(GridConChannelId.COMMAND_CONTROL_WORD_DISABLE_IPU_1, false);
		this.setNextWriteValueToBooleanWriteChannel(GridConChannelId.COMMAND_CONTROL_WORD_DISABLE_IPU_2, true);
		this.setNextWriteValueToBooleanWriteChannel(GridConChannelId.COMMAND_CONTROL_WORD_DISABLE_IPU_3, true);
		this.setNextWriteValueToBooleanWriteChannel(GridConChannelId.COMMAND_CONTROL_WORD_DISABLE_IPU_4, true);

		switch (this.config.inverterCount()) {
		case ONE:
			this.setNextWriteValueToBooleanWriteChannel(GridConChannelId.COMMAND_CONTROL_WORD_DISABLE_IPU_2, false);
			break;
		case TWO:
			this.setNextWriteValueToBooleanWriteChannel(GridConChannelId.COMMAND_CONTROL_WORD_DISABLE_IPU_3, false);
			break;
		case THREE:
			this.setNextWriteValueToBooleanWriteChannel(GridConChannelId.COMMAND_CONTROL_WORD_DISABLE_IPU_4, false);
			break;
		}
	}

	// TODO Shutdown system
//	private void stopSystem() {
//		log.info("Try to stop system");
//
//		// disable "Sync Approval" and "Ena IPU 4, 3, 2, 1" and add STOP command ->
//		// system should change state to "IDLE"
//		commandControlWord.set(PCSControlWordBitPosition.STOP.getBitPosition(), true);
//		commandControlWord.set(PCSControlWordBitPosition.SYNC_APPROVAL.getBitPosition(), false);
//		commandControlWord.set(PCSControlWordBitPosition.BLACKSTART_APPROVAL.getBitPosition(), false);
//		commandControlWord.set(PCSControlWordBitPosition.MODE_SELECTION.getBitPosition(), true);
//
//		commandControlWord.set(PCSControlWordBitPosition.DISABLE_IPU_1.getBitPosition(), true);
//		commandControlWord.set(PCSControlWordBitPosition.DISABLE_IPU_2.getBitPosition(), true);
//		commandControlWord.set(PCSControlWordBitPosition.DISABLE_IPU_3.getBitPosition(), true);
//		commandControlWord.set(PCSControlWordBitPosition.DISABLE_IPU_4.getBitPosition(), true);
//	}

	/**
	 * This converts a Bitset to its decimal value. Only works as long as the value
	 * of the Bitset does not exceed the range of an Integer.
	 * 
	 * @param bitSet The Bitset which should be converted
	 * @return The converted Integer
	 */
	private Integer convertToInteger(BitSet bitSet) {
		long[] l = bitSet.toLongArray();

		if (l.length == 0) {
			return 0;
		}
		return (int) l[0];
	}

	/**
	 * Writes parameters to all 4 IPUs !! Max charge/discharge power for IPUs always
	 * in absolute values !!
	 * 
	 * @param stringControlMode
	 */
	private void writeIPUParameters(float weightA, float weightB, float weightC, float pMaxDischargeIPU1,
			float pMaxDischargeIPU2, float pMaxDischargeIPU3, float pMaxChargeIPU1, float pMaxChargeIPU2,
			float pMaxChargeIPU3, float stringControlMode) {

		switch (this.config.inverterCount()) {
		case ONE:
			writeValueToChannel(GridConChannelId.INVERTER_1_CONTROL_DC_VOLTAGE_SETPOINT, 0f);
			writeValueToChannel(GridConChannelId.INVERTER_1_CONTROL_DC_CURRENT_SETPOINT, 0f);
			writeValueToChannel(GridConChannelId.INVERTER_1_CONTROL_U0_OFFSET_TO_CCU_VALUE, 0f);
			writeValueToChannel(GridConChannelId.INVERTER_1_CONTROL_F0_OFFSET_TO_CCU_VALUE, 0f);
			writeValueToChannel(GridConChannelId.INVERTER_1_CONTROL_Q_REF_OFFSET_TO_CCU_VALUE, 0f);
			writeValueToChannel(GridConChannelId.INVERTER_1_CONTROL_P_REF_OFFSET_TO_CCU_VALUE, 0f);

			// Gridcon needs negative values for discharge values, positive values for
			// charge values
			writeValueToChannel(GridConChannelId.INVERTER_1_CONTROL_P_MAX_DISCHARGE, -pMaxDischargeIPU1);
			writeValueToChannel(GridConChannelId.INVERTER_1_CONTROL_P_MAX_CHARGE, pMaxChargeIPU1);
			break;

		case TWO:
			writeValueToChannel(GridConChannelId.INVERTER_1_CONTROL_DC_VOLTAGE_SETPOINT, 0f);
			writeValueToChannel(GridConChannelId.INVERTER_1_CONTROL_DC_CURRENT_SETPOINT, 0f);
			writeValueToChannel(GridConChannelId.INVERTER_1_CONTROL_U0_OFFSET_TO_CCU_VALUE, 0f);
			writeValueToChannel(GridConChannelId.INVERTER_1_CONTROL_F0_OFFSET_TO_CCU_VALUE, 0f);
			writeValueToChannel(GridConChannelId.INVERTER_1_CONTROL_Q_REF_OFFSET_TO_CCU_VALUE, 0f);
			writeValueToChannel(GridConChannelId.INVERTER_1_CONTROL_P_REF_OFFSET_TO_CCU_VALUE, 0f);

			// Gridcon needs negative values for discharge values, positive values for
			// charge values
			writeValueToChannel(GridConChannelId.INVERTER_1_CONTROL_P_MAX_DISCHARGE, -pMaxDischargeIPU1);
			writeValueToChannel(GridConChannelId.INVERTER_1_CONTROL_P_MAX_CHARGE, pMaxChargeIPU1);

			writeValueToChannel(GridConChannelId.INVERTER_2_CONTROL_DC_VOLTAGE_SETPOINT, 0f);
			writeValueToChannel(GridConChannelId.INVERTER_2_CONTROL_DC_CURRENT_SETPOINT, 0f);
			writeValueToChannel(GridConChannelId.INVERTER_2_CONTROL_U0_OFFSET_TO_CCU_VALUE, 0f);
			writeValueToChannel(GridConChannelId.INVERTER_2_CONTROL_F0_OFFSET_TO_CCU_VALUE, 0f);
			writeValueToChannel(GridConChannelId.INVERTER_2_CONTROL_Q_REF_OFFSET_TO_CCU_VALUE, 0f);
			writeValueToChannel(GridConChannelId.INVERTER_2_CONTROL_P_REF_OFFSET_TO_CCU_VALUE, 0f);

			// Gridcon needs negative values for discharge values, positive values for
			// charge values
			writeValueToChannel(GridConChannelId.INVERTER_2_CONTROL_P_MAX_DISCHARGE, -pMaxDischargeIPU2);
			writeValueToChannel(GridConChannelId.INVERTER_2_CONTROL_P_MAX_CHARGE, pMaxChargeIPU2);
			break;

		case THREE:
			writeValueToChannel(GridConChannelId.INVERTER_1_CONTROL_DC_VOLTAGE_SETPOINT, 0f);
			writeValueToChannel(GridConChannelId.INVERTER_1_CONTROL_DC_CURRENT_SETPOINT, 0f);
			writeValueToChannel(GridConChannelId.INVERTER_1_CONTROL_U0_OFFSET_TO_CCU_VALUE, 0f);
			writeValueToChannel(GridConChannelId.INVERTER_1_CONTROL_F0_OFFSET_TO_CCU_VALUE, 0f);
			writeValueToChannel(GridConChannelId.INVERTER_1_CONTROL_Q_REF_OFFSET_TO_CCU_VALUE, 0f);
			writeValueToChannel(GridConChannelId.INVERTER_1_CONTROL_P_REF_OFFSET_TO_CCU_VALUE, 0f);

			// Gridcon needs negative values for discharge values, positive values for
			// charge values
			writeValueToChannel(GridConChannelId.INVERTER_1_CONTROL_P_MAX_DISCHARGE, -pMaxDischargeIPU1);
			writeValueToChannel(GridConChannelId.INVERTER_1_CONTROL_P_MAX_CHARGE, pMaxChargeIPU1);

			writeValueToChannel(GridConChannelId.INVERTER_2_CONTROL_DC_VOLTAGE_SETPOINT, 0f);
			writeValueToChannel(GridConChannelId.INVERTER_2_CONTROL_DC_CURRENT_SETPOINT, 0f);
			writeValueToChannel(GridConChannelId.INVERTER_2_CONTROL_U0_OFFSET_TO_CCU_VALUE, 0f);
			writeValueToChannel(GridConChannelId.INVERTER_2_CONTROL_F0_OFFSET_TO_CCU_VALUE, 0f);
			writeValueToChannel(GridConChannelId.INVERTER_2_CONTROL_Q_REF_OFFSET_TO_CCU_VALUE, 0f);
			writeValueToChannel(GridConChannelId.INVERTER_2_CONTROL_P_REF_OFFSET_TO_CCU_VALUE, 0f);

			// Gridcon needs negative values for discharge values, positive values for
			// charge values
			writeValueToChannel(GridConChannelId.INVERTER_2_CONTROL_P_MAX_DISCHARGE, -pMaxDischargeIPU2);
			writeValueToChannel(GridConChannelId.INVERTER_2_CONTROL_P_MAX_CHARGE, pMaxChargeIPU2);

			writeValueToChannel(GridConChannelId.INVERTER_3_CONTROL_DC_VOLTAGE_SETPOINT, 0f);
			writeValueToChannel(GridConChannelId.INVERTER_3_CONTROL_DC_CURRENT_SETPOINT, 0f);
			writeValueToChannel(GridConChannelId.INVERTER_3_CONTROL_U0_OFFSET_TO_CCU_VALUE, 0f);
			writeValueToChannel(GridConChannelId.INVERTER_3_CONTROL_F0_OFFSET_TO_CCU_VALUE, 0f);
			writeValueToChannel(GridConChannelId.INVERTER_3_CONTROL_Q_REF_OFFSET_TO_CCU_VALUE, 0f);
			writeValueToChannel(GridConChannelId.INVERTER_3_CONTROL_P_REF_OFFSET_TO_CCU_VALUE, 0f);

			// Gridcon needs negative values for discharge values, positive values for
			// charge values
			writeValueToChannel(GridConChannelId.INVERTER_3_CONTROL_P_MAX_DISCHARGE, -pMaxDischargeIPU3);
			writeValueToChannel(GridConChannelId.INVERTER_3_CONTROL_P_MAX_CHARGE, pMaxChargeIPU3);
			break;
		}

//		writeValueToChannel(GridConChannelId.DCDC_CONTROL_DC_VOLTAGE_SETPOINT, 0f);
//		writeValueToChannel(GridConChannelId.DCDC_CONTROL_WEIGHT_STRING_A, 0f);
//		writeValueToChannel(GridConChannelId.DCDC_CONTROL_WEIGHT_STRING_B, 0f);
//		writeValueToChannel(GridConChannelId.DCDC_CONTROL_WEIGHT_STRING_C, 0f);
		writeValueToChannel(GridConChannelId.DCDC_CONTROL_I_REF_STRING_A, 0f);
		writeValueToChannel(GridConChannelId.DCDC_CONTROL_I_REF_STRING_B, 0f);
		writeValueToChannel(GridConChannelId.DCDC_CONTROL_I_REF_STRING_C, 0f);
//		writeValueToChannel(GridConChannelId.DCDC_CONTROL_STRING_CONTROL_MODE, 0f); //

		// The value of 800 Volt is given by MR as a good reference value
		writeValueToChannel(GridConChannelId.DCDC_CONTROL_DC_VOLTAGE_SETPOINT, DC_LINK_VOLTAGE_SETPOINT);
		writeValueToChannel(GridConChannelId.DCDC_CONTROL_WEIGHT_STRING_A, weightA);
		writeValueToChannel(GridConChannelId.DCDC_CONTROL_WEIGHT_STRING_B, weightB);
		writeValueToChannel(GridConChannelId.DCDC_CONTROL_WEIGHT_STRING_C, weightC);
		// The value '73' implies that all 3 strings are in weighting mode
		writeValueToChannel(GridConChannelId.DCDC_CONTROL_STRING_CONTROL_MODE, stringControlMode); //
	}

	private void doErrorHandling() throws OpenemsNamedException {
		StateChannel c = getErrorChannel();
		if (c == null) {
			System.out.println("Channel is null......");
			return;
		}
		c.setNextValue(true);
		if (((ErrorDoc) c.channelId().doc()).isNeedsHardReset()) {
			doHardRestart();
		} else {
			log.info("try to acknowledge errors");
			acknowledgeErrors();
		}
	}

	/**
	 * Gets the (first) active Error-Channel; or null if no Error is present.
	 * 
	 * @return the Error-Channel or null
	 */
	private StateChannel getErrorChannel() {
		IntegerReadChannel errorCodeChannel = this.channel(GridConChannelId.CCU_ERROR_CODE);
		Optional<Integer> errorCodeOpt = errorCodeChannel.value().asOptional();
		if (errorCodeOpt.isPresent() && errorCodeOpt.get() != 0) {
			int code = errorCodeOpt.get();
			System.out.println("Code read: " + code + " ==> hex: " + Integer.toHexString(code));
			code = code >> 8;
			System.out.println("Code >> 8 read: " + code + " ==> hex: " + Integer.toHexString(code));
			log.info("Error code is present --> " + code);
			io.openems.edge.common.channel.ChannelId id = errorChannelIds.get(code);
			return this.channel(id);
		}
		return null;
	}

	private void doHardRestart() {
		try {
			log.info("in doHardRestart");
			if (timestampMrGridconWasSwitchedOff == null) {
				log.info("timestampMrGridconWasSwitchedOff was not set yet! try to write 'true' to channelHardReset!");
				BooleanWriteChannel channelHardReset = this.componentManager
						.getChannel(ChannelAddress.fromString(this.config.outputMRHardReset()));
				channelHardReset.setNextWriteValue(true);
				timestampMrGridconWasSwitchedOff = LocalDateTime.now();
			}
		} catch (IllegalArgumentException | OpenemsNamedException e) {
			log.error("Problem occurred while activating hardware switch to restart Mr. Gridcon!");
			e.printStackTrace();
		}

	}

	/**
	 * This sends an ACKNOWLEDGE message. This does not fix the error. If the error
	 * was fixed previously the system should continue operating normally. If not a
	 * manual restart may be necessary.
	 * 
	 * @throws OpenemsException
	 */
	private void acknowledgeErrors() throws OpenemsNamedException {
		if ( //
		lastTimeAcknowledgeCommandoWasSent == null || //
				LocalDateTime.now().isAfter(lastTimeAcknowledgeCommandoWasSent.plusSeconds(ACKNOWLEDGE_TIME_SECONDS)) //
		) {
			this.setNextWriteValueToBooleanWriteChannel(GridConChannelId.COMMAND_CONTROL_WORD_ACKNOWLEDGE, true);
			lastTimeAcknowledgeCommandoWasSent = LocalDateTime.now();
		}
	}

	@Override
	public String debugLog() {
		return "State:" + this.getCurrentState().toString() + "," + "L:"
				+ this.channel(SymmetricEss.ChannelId.ACTIVE_POWER).value().asString() //
				+ "," + this.getGridMode().value().asEnum().getName();
	}

	private CCUState getCurrentState() {
		if (((BooleanReadChannel) this.channel(GridConChannelId.CCU_STATE_IDLE)).value().asOptional().orElse(false)) {
			return CCUState.IDLE;
		}

		if (((BooleanReadChannel) this.channel(GridConChannelId.CCU_STATE_PRECHARGE)).value().asOptional()
				.orElse(false)) {
			return CCUState.PRECHARGE;
		}
		if (((BooleanReadChannel) this.channel(GridConChannelId.CCU_STATE_STOP_PRECHARGE)).value().asOptional()
				.orElse(false)) {
			return CCUState.STOP_PRECHARGE;
		}
		if (((BooleanReadChannel) this.channel(GridConChannelId.CCU_STATE_READY)).value().asOptional().orElse(false)) {
			return CCUState.READY;
		}
		if (((BooleanReadChannel) this.channel(GridConChannelId.CCU_STATE_PAUSE)).value().asOptional().orElse(false)) {
			return CCUState.PAUSE;
		}
		if (((BooleanReadChannel) this.channel(GridConChannelId.CCU_STATE_RUN)).value().asOptional().orElse(false)) {
			return CCUState.RUN;
		}
		if (((BooleanReadChannel) this.channel(GridConChannelId.CCU_STATE_ERROR)).value().asOptional().orElse(false)) {
			return CCUState.ERROR;
		}
		if (((BooleanReadChannel) this.channel(GridConChannelId.CCU_STATE_VOLTAGE_RAMPING_UP)).value().asOptional()
				.orElse(false)) {
			return CCUState.VOLTAGE_RAMPING_UP;
		}
		if (((BooleanReadChannel) this.channel(GridConChannelId.CCU_STATE_OVERLOAD)).value().asOptional()
				.orElse(false)) {
			return CCUState.OVERLOAD;
		}
		if (((BooleanReadChannel) this.channel(GridConChannelId.CCU_STATE_SHORT_CIRCUIT_DETECTED)).value().asOptional()
				.orElse(false)) {
			return CCUState.SHORT_CIRCUIT_DETECTED;
		}
		if (((BooleanReadChannel) this.channel(GridConChannelId.CCU_STATE_DERATING_POWER)).value().asOptional()
				.orElse(false)) {
			return CCUState.DERATING_POWER;
		}
		if (((BooleanReadChannel) this.channel(GridConChannelId.CCU_STATE_DERATING_HARMONICS)).value().asOptional()
				.orElse(false)) {
			return CCUState.DERATING_HARMONICS;
		}
		if (((BooleanReadChannel) this.channel(GridConChannelId.CCU_STATE_SIA_ACTIVE)).value().asOptional()
				.orElse(false)) {
			return CCUState.SIA_ACTIVE;
		}

		return CCUState.UNDEFINED;
	}

	@Override
	public Power getPower() {
		return this.power;
	}

	@Override
	public Constraint[] getStaticConstraints() {
		GridMode gridMode = this.getGridMode().value().asEnum();
		if (getCurrentState() != CCUState.RUN || gridMode != GridMode.ON_GRID) {
			return new Constraint[] {
					this.createPowerConstraint("Inverter not ready", Phase.ALL, Pwr.ACTIVE, Relationship.EQUALS, 0),
					this.createPowerConstraint("Inverter not ready", Phase.ALL, Pwr.REACTIVE, Relationship.EQUALS, 0) };
		} else {
			return Power.NO_CONSTRAINTS;
		}
	}

	@Override
	public void applyPower(int activePower, int reactivePower) throws OpenemsNamedException {
		switch (this.getStateMachine()) {
		case IDLE:
		case UNDEFINED:
			return;

		case ONGRID_NORMAL_OPERATION:
			break;
		}
		
		// TODO Schreibe Pref, Qref und weights

		
		
		
		
		doStringWeighting(activePower, reactivePower);

		Optional<Integer> maxApparentPower = this.getMaxApparentPower().value().asOptional();
		float activePowerFactor = 0f;
		float reactivePowerFactor = 0f;
		if (maxApparentPower.isPresent()) {
			/*
			 * !! signum, MR calculates negative values as discharge, positive as charge.
			 * Gridcon sets the (dis)charge according to a percentage of the
			 * MAX_APPARENT_POWER. So 0.1 => 10% of max power. Values should never take
			 * values lower than -1 or higher than 1.
			 */
			activePowerFactor = -activePower / maxApparentPower.get();
			reactivePowerFactor = -reactivePower / maxApparentPower.get();
		}
		writeValueToChannel(GridConChannelId.COMMAND_CONTROL_PARAMETER_P_REF, activePowerFactor);
		writeValueToChannel(GridConChannelId.COMMAND_CONTROL_PARAMETER_Q_REF, reactivePowerFactor);
	}

	private void doStringWeighting(int activePower, int reactivePower) throws OpenemsNamedException {
		int weightA = 0;
		int weightB = 0;
		int weightC = 0;

		Battery batteryStringA = this.componentManager.getComponent(this.config.batteryStringA_id());
		Battery batteryStringB = this.componentManager.getComponent(this.config.batteryStringB_id());
		Battery batteryStringC = this.componentManager.getComponent(this.config.batteryStringC_id());

		// weight strings according to max allowed current
		// use values for discharging
		// weight zero power as discharging
		if (activePower > 0) {

			if (batteryStringA != null) {
				weightA = batteryStringA.getDischargeMaxCurrent().value().asOptional().orElse(0);
				// if minSoc is reached, do not allow further discharging
				if (batteryStringA.getSoc().value().asOptional().orElse(0) <= this.config.minSocBatteryA()) {
					weightA = 0;
				}
			}

			if (batteryStringB != null) {
				weightB = batteryStringB.getDischargeMaxCurrent().value().asOptional().orElse(0);
				// if minSoc is reached, do not allow further discharging
				if (batteryStringB.getSoc().value().asOptional().orElse(0) <= this.config.minSocBatteryB()) {
					weightB = 0;
				}
			}

			if (batteryStringC != null) {
				weightC = batteryStringC.getDischargeMaxCurrent().value().asOptional().orElse(0);
				// if minSoc is reached, do not allow further discharging
				if (batteryStringC.getSoc().value().asOptional().orElse(0) <= this.config.minSocBatteryC()) {
					weightC = 0;
				}
			}
		} else if (activePower < 0) { // use values for charging
			if (batteryStringA != null) {
				weightA = batteryStringA.getChargeMaxCurrent().value().asOptional().orElse(0);
			}
			if (batteryStringB != null) {
				weightB = batteryStringB.getChargeMaxCurrent().value().asOptional().orElse(0);
			}
			if (batteryStringC != null) {
				weightC = batteryStringC.getChargeMaxCurrent().value().asOptional().orElse(0);
			}
		} else { // active power is zero

			if (batteryStringA != null && batteryStringB != null && batteryStringC != null) { // ABC
				Optional<Integer> vAopt = batteryStringA.getVoltage().value().asOptional();
				Optional<Integer> vBopt = batteryStringB.getVoltage().value().asOptional();
				Optional<Integer> vCopt = batteryStringC.getVoltage().value().asOptional();
				if (vAopt.isPresent() && vBopt.isPresent() && vCopt.isPresent()) {
					int min = Math.min(vAopt.get(), Math.min(vBopt.get(), vCopt.get()));
					weightA = vAopt.get() - min;
					weightB = vBopt.get() - min;
					weightC = vCopt.get() - min;
				}
			} else if (batteryStringA != null && batteryStringB != null && batteryStringC == null) { // AB
				Optional<Integer> vAopt = batteryStringA.getVoltage().value().asOptional();
				Optional<Integer> vBopt = batteryStringB.getVoltage().value().asOptional();
				if (vAopt.isPresent() && vBopt.isPresent()) {
					int min = Math.min(vAopt.get(), vBopt.get());
					weightA = vAopt.get() - min;
					weightB = vBopt.get() - min;
				}
			} else if (batteryStringA != null && batteryStringB == null && batteryStringC != null) { // AC
				Optional<Integer> vAopt = batteryStringA.getVoltage().value().asOptional();
				Optional<Integer> vCopt = batteryStringC.getVoltage().value().asOptional();
				if (vAopt.isPresent() && vCopt.isPresent()) {
					int min = Math.min(vAopt.get(), vCopt.get());
					weightA = vAopt.get() - min;
					weightC = vCopt.get() - min;
				}
			} else if (batteryStringA == null && batteryStringB != null && batteryStringC != null) { // BC
				Optional<Integer> vBopt = batteryStringB.getVoltage().value().asOptional();
				Optional<Integer> vCopt = batteryStringC.getVoltage().value().asOptional();
				if (vBopt.isPresent() && vCopt.isPresent()) {
					int min = Math.min(vBopt.get(), vCopt.get());
					weightB = vBopt.get() - min;
					weightC = vCopt.get() - min;
				}
			}
		}

		// TODO discuss if this is correct!
		int maxChargePower1 = 0;
		int maxChargePower2 = 0;
		int maxChargePower3 = 0;
		int maxDischargePower1 = 0;
		int maxDischargePower2 = 0;
		int maxDischargePower3 = 0;
		if (batteryStringA != null) {
			maxChargePower1 = batteryStringA.getChargeMaxCurrent().value().asOptional().orElse(0)
					* batteryStringA.getChargeMaxVoltage().value().asOptional().orElse(0);
			maxDischargePower1 = batteryStringA.getDischargeMaxCurrent().value().asOptional().orElse(0)
					* batteryStringA.getDischargeMinVoltage().value().asOptional().orElse(0);
		}

		if (batteryStringB != null) {
			maxChargePower2 = batteryStringB.getChargeMaxCurrent().value().asOptional().orElse(0)
					* batteryStringB.getChargeMaxVoltage().value().asOptional().orElse(0);
			maxDischargePower2 = batteryStringB.getDischargeMaxCurrent().value().asOptional().orElse(0)
					* batteryStringB.getDischargeMinVoltage().value().asOptional().orElse(0);
		}
		if (batteryStringC != null) {
			maxChargePower3 = batteryStringC.getChargeMaxCurrent().value().asOptional().orElse(0)
					* batteryStringC.getChargeMaxVoltage().value().asOptional().orElse(0);

			maxDischargePower3 = batteryStringC.getDischargeMaxCurrent().value().asOptional().orElse(0)
					* batteryStringC.getDischargeMinVoltage().value().asOptional().orElse(0);

		}

		log.info("doStringweighting(); active Power: " + activePower + "; weightA: " + weightA + "; weightB: " + weightB
				+ "; weightC: " + weightC);

		writeIPUParameters(weightA, weightB, weightC, maxDischargePower1, maxDischargePower2, maxDischargePower3,
				maxChargePower1, maxChargePower2, maxChargePower3, getWeightingMode());
	}

	/** Writes the given value into the channel */
	// TODO should throws OpenemsException
	void writeValueToChannel(GridConChannelId channelId, Object value) {
		try {
			((WriteChannel<?>) this.channel(channelId)).setNextWriteValueFromObject(value);
		} catch (OpenemsNamedException e) {
			e.printStackTrace();
			log.error("Problem occurred during writing '" + value + "' to channel " + channelId.name());
		}
	}

	@Override
	public int getPowerPrecision() {
		return 100;
	}

	private void handleOffGridState() throws OpenemsNamedException {

		boolean disableIpu1 = false;
		boolean disableIpu2 = true;
		boolean disableIpu3 = true;
		boolean disableIpu4 = true;

		switch (this.config.inverterCount()) {
		case ONE:
			disableIpu2 = false;
			break;
		case TWO:
			disableIpu2 = false;
			disableIpu3 = false;
			break;
		case THREE:
			disableIpu2 = false;
			disableIpu3 = false;
			disableIpu4 = false;
			break;
		}

		// send play command
//		commandControlWord.set(PCSControlWordBitPosition.PLAY.getBitPosition(), true);

		this.setNextWriteValueToBooleanWriteChannel(GridConChannelId.COMMAND_CONTROL_WORD_DISABLE_IPU_1, disableIpu1);
		this.setNextWriteValueToBooleanWriteChannel(GridConChannelId.COMMAND_CONTROL_WORD_DISABLE_IPU_2, disableIpu2);
		this.setNextWriteValueToBooleanWriteChannel(GridConChannelId.COMMAND_CONTROL_WORD_DISABLE_IPU_3, disableIpu3);
		this.setNextWriteValueToBooleanWriteChannel(GridConChannelId.COMMAND_CONTROL_WORD_DISABLE_IPU_4, disableIpu4);
		// Always set Voltage Control Mode + Blackstart Approval
		this.setNextWriteValueToBooleanWriteChannel(GridConChannelId.COMMAND_CONTROL_WORD_BLACKSTART_APPROVAL, true);
		this.setNextWriteValueToBooleanWriteChannel(GridConChannelId.COMMAND_CONTROL_WORD_SYNC_APPROVAL, false);
		this.setNextWriteValueToBooleanWriteChannel(GridConChannelId.COMMAND_CONTROL_WORD_MODE_SELECTION, false);
		this.setNextWriteValueToBooleanWriteChannel(
				GridConChannelId.COMMAND_CONTROL_WORD_ACTIVATE_SHORT_CIRCUIT_HANDLING, false);

		// Always set OutputSyncDeviceBridge ON in Off-Grid state
		log.info("Set K1 ON");
		this.setOutputSyncDeviceBridge(true);
		// TODO check if OutputSyncDeviceBridge was actually set to ON via
		// inputSyncDeviceBridgeComponent. On Error switch off the MR.

		if (offGridDetected == null) {
			offGridDetected = LocalDateTime.now();
			return;
		}
		if (offGridDetected.plusSeconds(DO_NOTHING_IN_OFFGRID_FOR_THE_FIRST_SECONDS).isAfter(LocalDateTime.now())) {
			return;
		}

		// Measured by Grid-Meter, grid Values
		SymmetricMeter gridMeter = this.componentManager.getComponent(this.config.meter());

		int gridFreq = gridMeter.getFrequency().value().orElse(-1);
		int gridVolt = gridMeter.getVoltage().value().orElse(-1);

		log.info("GridFreq: " + gridFreq + ", GridVolt: " + gridVolt);

		if (gridFreq == 0 || gridFreq < 49_700 || gridFreq > 50_300 || //
				gridVolt == 0 || gridVolt < 215_000 || gridVolt > 245_000) {
			log.info("Off-Grid -> F/U 1");
			/*
			 * Off-Grid
			 */
			writeValueToChannel(GridConChannelId.COMMAND_CONTROL_PARAMETER_U0, 1.0f);
			writeValueToChannel(GridConChannelId.COMMAND_CONTROL_PARAMETER_F0, 1.0f);

		} else {
			/*
			 * Going On-Grid
			 */
			int invSetFreq = gridFreq + 20; // add 20 mHz
			int invSetVolt = gridVolt + 5_000; // add 5 V
			float invSetFreqNormalized = invSetFreq / 50_000f;
			float invSetVoltNormalized = invSetVolt / 230_000f;
			log.info("Going On-Grid -> F/U " + invSetFreq + ", " + invSetVolt + ", " + invSetFreqNormalized + ", "
					+ invSetVoltNormalized);
			writeValueToChannel(GridConChannelId.COMMAND_CONTROL_PARAMETER_U0, invSetVoltNormalized);
			writeValueToChannel(GridConChannelId.COMMAND_CONTROL_PARAMETER_F0, invSetFreqNormalized);
		}
	}

	/**
	 * Gets all Batteries.
	 * 
	 * @return a collection of Batteries; guaranteed to be not-null.
	 */
	private Collection<Battery> getBatteries() {
		Collection<Battery> batteries = new ArrayList<>();
		{
			Battery batteryStringA;
			try {
				batteryStringA = this.componentManager.getComponent(this.config.batteryStringA_id());
				if (batteryStringA != null) {
					batteries.add(batteryStringA);
				}
			} catch (OpenemsNamedException e) {
				// ignore
			}
		}
		{
			try {
				Battery batteryStringB = this.componentManager.getComponent(this.config.batteryStringB_id());
				if (batteryStringB != null) {
					batteries.add(batteryStringB);
				}
			} catch (OpenemsNamedException e) {
				// ignore
			}
		}
		{
			try {
				Battery batteryStringC = this.componentManager.getComponent(this.config.batteryStringC_id());
				if (batteryStringC != null) {
					batteries.add(batteryStringC);
				}
			} catch (OpenemsNamedException e) {
				// ignore
			}
		}
		return batteries;
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		int inverterCount = this.config.inverterCount().getCount();
		ModbusProtocol result = new ModbusProtocol(this, //
				/*
				 * CCU State
				 */
				new FC3ReadRegistersTask(32528, Priority.HIGH, //
						m(new BitsWordElement(32528, this) //
								.bit(0, GridConChannelId.CCU_STATE_IDLE) //
								.bit(1, GridConChannelId.CCU_STATE_PRECHARGE) //
								.bit(2, GridConChannelId.CCU_STATE_STOP_PRECHARGE) //
								.bit(3, GridConChannelId.CCU_STATE_READY) //
								.bit(4, GridConChannelId.CCU_STATE_PAUSE) //
								.bit(5, GridConChannelId.CCU_STATE_RUN) //
								.bit(6, GridConChannelId.CCU_STATE_ERROR) //
								.bit(7, GridConChannelId.CCU_STATE_VOLTAGE_RAMPING_UP) //
								.bit(8, GridConChannelId.CCU_STATE_OVERLOAD) //
								.bit(9, GridConChannelId.CCU_STATE_SHORT_CIRCUIT_DETECTED) //
								.bit(10, GridConChannelId.CCU_STATE_DERATING_POWER) //
								.bit(11, GridConChannelId.CCU_STATE_DERATING_HARMONICS) //
								.bit(12, GridConChannelId.CCU_STATE_SIA_ACTIVE) //
						), //
						new DummyRegisterElement(32529),
						m(GridConChannelId.CCU_ERROR_CODE,
								new UnsignedDoublewordElement(32530).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.CCU_VOLTAGE_U12,
								new FloatDoublewordElement(32532).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.CCU_VOLTAGE_U23,
								new FloatDoublewordElement(32534).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.CCU_VOLTAGE_U31,
								new FloatDoublewordElement(32536).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.CCU_CURRENT_IL1,
								new FloatDoublewordElement(32538).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.CCU_CURRENT_IL2,
								new FloatDoublewordElement(32540).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.CCU_CURRENT_IL3,
								new FloatDoublewordElement(32542).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.CCU_POWER_P, new FloatDoublewordElement(32544).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.CCU_POWER_Q, new FloatDoublewordElement(32546).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.CCU_FREQUENCY, new FloatDoublewordElement(32548).wordOrder(WordOrder.LSWMSW)) //
				),
				/*
				 * Commands
				 */
				new FC16WriteRegistersTask(32560, //
						m(new BitsWordElement(32560, this) //
								.bit(12, GridConChannelId.COMMAND_CONTROL_WORD_DISABLE_IPU_4) //
								.bit(13, GridConChannelId.COMMAND_CONTROL_WORD_DISABLE_IPU_3) //
								.bit(14, GridConChannelId.COMMAND_CONTROL_WORD_DISABLE_IPU_2) //
								.bit(15, GridConChannelId.COMMAND_CONTROL_WORD_DISABLE_IPU_1) //
						).debug(), //
						m(new BitsWordElement(32561, this) //
								.bit(0, GridConChannelId.COMMAND_CONTROL_WORD_PLAY) //
								.bit(1, GridConChannelId.COMMAND_CONTROL_WORD_READY) //
								.bit(2, GridConChannelId.COMMAND_CONTROL_WORD_ACKNOWLEDGE) //
								.bit(3, GridConChannelId.COMMAND_CONTROL_WORD_STOP) //

								.bit(4, GridConChannelId.COMMAND_CONTROL_WORD_BLACKSTART_APPROVAL) //
								.bit(5, GridConChannelId.COMMAND_CONTROL_WORD_SYNC_APPROVAL) //
								.bit(6, GridConChannelId.COMMAND_CONTROL_WORD_ACTIVATE_SHORT_CIRCUIT_HANDLING) //
								.bit(7, GridConChannelId.COMMAND_CONTROL_WORD_MODE_SELECTION) //

								.bit(8, GridConChannelId.COMMAND_CONTROL_WORD_TRIGGER_SIA) //
								.bit(9, GridConChannelId.COMMAND_CONTROL_WORD_ACTIVATE_HARMONIC_COMPENSATION) //
								.bit(10, GridConChannelId.COMMAND_CONTROL_WORD_ID_1_SD_CARD_PARAMETER_SET) //
								.bit(11, GridConChannelId.COMMAND_CONTROL_WORD_ID_2_SD_CARD_PARAMETER_SET) //

								.bit(12, GridConChannelId.COMMAND_CONTROL_WORD_ID_3_SD_CARD_PARAMETER_SET) //
								.bit(13, GridConChannelId.COMMAND_CONTROL_WORD_ID_4_SD_CARD_PARAMETER_SET) //
						).debug(), //
						m(GridConChannelId.COMMAND_ERROR_CODE_FEEDBACK,
								new UnsignedDoublewordElement(32562).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.COMMAND_CONTROL_PARAMETER_U0,
								new FloatDoublewordElement(32564).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.COMMAND_CONTROL_PARAMETER_F0,
								new FloatDoublewordElement(32566).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.COMMAND_CONTROL_PARAMETER_Q_REF,
								new FloatDoublewordElement(32568).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.COMMAND_CONTROL_PARAMETER_P_REF,
								new FloatDoublewordElement(32570).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.COMMAND_TIME_SYNC_DATE,
								new UnsignedDoublewordElement(32572).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.COMMAND_TIME_SYNC_TIME,
								new UnsignedDoublewordElement(32574).wordOrder(WordOrder.LSWMSW)) //
				),
				/*
				 * Commands Mirror
				 */
				new FC3ReadRegistersTask(32880, Priority.LOW, //
						m(new BitsWordElement(32880, this) //
								.bit(12, GridConChannelId.COMMAND_CONTROL_WORD_DISABLE_IPU_4) //
								.bit(13, GridConChannelId.COMMAND_CONTROL_WORD_DISABLE_IPU_3) //
								.bit(14, GridConChannelId.COMMAND_CONTROL_WORD_DISABLE_IPU_2) //
								.bit(16, GridConChannelId.COMMAND_CONTROL_WORD_DISABLE_IPU_1) //
						), //
						m(new BitsWordElement(32881, this) //
								.bit(0, GridConChannelId.COMMAND_CONTROL_WORD_PLAY) //
								.bit(1, GridConChannelId.COMMAND_CONTROL_WORD_READY) //
								.bit(2, GridConChannelId.COMMAND_CONTROL_WORD_ACKNOWLEDGE) //
								.bit(3, GridConChannelId.COMMAND_CONTROL_WORD_STOP) //
								.bit(4, GridConChannelId.COMMAND_CONTROL_WORD_BLACKSTART_APPROVAL) //
								.bit(5, GridConChannelId.COMMAND_CONTROL_WORD_SYNC_APPROVAL) //
								.bit(6, GridConChannelId.COMMAND_CONTROL_WORD_ACTIVATE_SHORT_CIRCUIT_HANDLING) //
								.bit(7, GridConChannelId.COMMAND_CONTROL_WORD_MODE_SELECTION) //
								.bit(8, GridConChannelId.COMMAND_CONTROL_WORD_TRIGGER_SIA) //
								.bit(9, GridConChannelId.COMMAND_CONTROL_WORD_ACTIVATE_HARMONIC_COMPENSATION) //
								.bit(10, GridConChannelId.COMMAND_CONTROL_WORD_ID_1_SD_CARD_PARAMETER_SET) //
								.bit(11, GridConChannelId.COMMAND_CONTROL_WORD_ID_2_SD_CARD_PARAMETER_SET) //
								.bit(12, GridConChannelId.COMMAND_CONTROL_WORD_ID_3_SD_CARD_PARAMETER_SET) //
								.bit(13, GridConChannelId.COMMAND_CONTROL_WORD_ID_4_SD_CARD_PARAMETER_SET) //
						), //
						m(GridConChannelId.COMMAND_ERROR_CODE_FEEDBACK,
								new UnsignedDoublewordElement(32882).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.COMMAND_CONTROL_PARAMETER_U0,
								new FloatDoublewordElement(32884).wordOrder(WordOrder.LSWMSW)),
						m(GridConChannelId.COMMAND_CONTROL_PARAMETER_F0,
								new FloatDoublewordElement(32886).wordOrder(WordOrder.LSWMSW)),
						m(GridConChannelId.COMMAND_CONTROL_PARAMETER_Q_REF,
								new FloatDoublewordElement(32888).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.COMMAND_CONTROL_PARAMETER_P_REF,
								new FloatDoublewordElement(32890).wordOrder(WordOrder.LSWMSW)) //
				),
				/*
				 * Control Parameters
				 */
				new FC16WriteRegistersTask(32592, //
						m(GridConChannelId.CONTROL_PARAMETER_U_Q_DROOP_MAIN,
								new FloatDoublewordElement(32592).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.CONTROL_PARAMETER_U_Q_DROOP_T1_MAIN,
								new FloatDoublewordElement(32594).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.CONTROL_PARAMETER_F_P_DROOP_MAIN,
								new FloatDoublewordElement(32596).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.CONTROL_PARAMETER_F_P_DROOP_T1_MAIN,
								new FloatDoublewordElement(32598).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.CONTROL_PARAMETER_Q_U_DROOP_MAIN,
								new FloatDoublewordElement(32600).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.CONTROL_PARAMETER_Q_U_DEAD_BAND,
								new FloatDoublewordElement(32602).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.CONTROL_PARAMETER_Q_LIMIT,
								new FloatDoublewordElement(32604).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.CONTROL_PARAMETER_P_F_DROOP_MAIN,
								new FloatDoublewordElement(32606).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.CONTROL_PARAMETER_P_F_DEAD_BAND,
								new FloatDoublewordElement(32608).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.CONTROL_PARAMETER_P_U_DROOP,
								new FloatDoublewordElement(32610).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.CONTROL_PARAMETER_P_U_DEAD_BAND,
								new FloatDoublewordElement(32612).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.CONTROL_PARAMETER_P_U_MAX_CHARGE,
								new FloatDoublewordElement(32614).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.CONTROL_PARAMETER_P_U_MAX_DISCHARGE,
								new FloatDoublewordElement(32616).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.CONTROL_PARAMETER_P_CONTROL_MODE,
								new FloatDoublewordElement(32618).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.CONTROL_PARAMETER_P_CONTROL_LIM_TWO,
								new FloatDoublewordElement(32620).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.CONTROL_PARAMETER_P_CONTROL_LIM_ONE,
								new FloatDoublewordElement(32622).wordOrder(WordOrder.LSWMSW)) //
				),
				/*
				 * Control Parameters Mirror
				 */
				new FC3ReadRegistersTask(32912, Priority.LOW,
						m(GridConChannelId.CONTROL_PARAMETER_U_Q_DROOP_MAIN,
								new FloatDoublewordElement(32912).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.CONTROL_PARAMETER_U_Q_DROOP_T1_MAIN,
								new FloatDoublewordElement(32914).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.CONTROL_PARAMETER_F_P_DROOP_MAIN,
								new FloatDoublewordElement(32916).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.CONTROL_PARAMETER_F_P_DROOP_T1_MAIN,
								new FloatDoublewordElement(32918).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.CONTROL_PARAMETER_Q_U_DROOP_MAIN,
								new FloatDoublewordElement(32920).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.CONTROL_PARAMETER_Q_U_DEAD_BAND,
								new FloatDoublewordElement(32922).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.CONTROL_PARAMETER_Q_LIMIT,
								new FloatDoublewordElement(32924).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.CONTROL_PARAMETER_P_F_DROOP_MAIN,
								new FloatDoublewordElement(32926).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.CONTROL_PARAMETER_P_F_DEAD_BAND,
								new FloatDoublewordElement(32928).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.CONTROL_PARAMETER_P_U_DROOP,
								new FloatDoublewordElement(32930).wordOrder(WordOrder.LSWMSW)) //
				));

		if (inverterCount > 0) {
			/*
			 * At least 1 Inverter -> Add IPU 1
			 */
			result.addTasks(//
					/*
					 * IPU 1 State
					 */
					new FC3ReadRegistersTask(33168, Priority.LOW, //
							m(GridConChannelId.INVERTER_1_STATUS_STATE_MACHINE, new UnsignedWordElement(33168)), //
							m(GridConChannelId.INVERTER_1_STATUS_MCU, new UnsignedWordElement(33169)), //
							m(GridConChannelId.INVERTER_1_STATUS_FILTER_CURRENT,
									new FloatDoublewordElement(33170).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_1_STATUS_DC_LINK_POSITIVE_VOLTAGE,
									new FloatDoublewordElement(33172).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_1_STATUS_DC_LINK_NEGATIVE_VOLTAGE,
									new FloatDoublewordElement(33174).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_1_STATUS_DC_LINK_CURRENT,
									new FloatDoublewordElement(33176).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_1_STATUS_DC_LINK_ACTIVE_POWER,
									new FloatDoublewordElement(33178).wordOrder(WordOrder.LSWMSW),
									ElementToChannelConverter.INVERT), //
							m(GridConChannelId.INVERTER_1_STATUS_DC_LINK_UTILIZATION,
									new FloatDoublewordElement(33180).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_1_STATUS_FAN_SPEED_MAX,
									new UnsignedDoublewordElement(33182).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_1_STATUS_FAN_SPEED_MIN,
									new UnsignedDoublewordElement(33184).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_1_STATUS_TEMPERATURE_IGBT_MAX,
									new FloatDoublewordElement(33186).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_1_STATUS_TEMPERATURE_MCU_BOARD,
									new FloatDoublewordElement(33188).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_1_STATUS_TEMPERATURE_GRID_CHOKE,
									new FloatDoublewordElement(33190).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_1_STATUS_TEMPERATURE_INVERTER_CHOKE,
									new FloatDoublewordElement(33192).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_1_STATUS_RESERVE_1,
									new FloatDoublewordElement(33194).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_1_STATUS_RESERVE_2,
									new FloatDoublewordElement(33196).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_1_STATUS_RESERVE_3,
									new FloatDoublewordElement(33198).wordOrder(WordOrder.LSWMSW)) //
					),
					/*
					 * IPU 1 Control Parameters
					 */
					new FC16WriteRegistersTask(32624, //
							m(GridConChannelId.INVERTER_1_CONTROL_DC_VOLTAGE_SETPOINT,
									new FloatDoublewordElement(32624).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_1_CONTROL_DC_CURRENT_SETPOINT,
									new FloatDoublewordElement(32626).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_1_CONTROL_U0_OFFSET_TO_CCU_VALUE,
									new FloatDoublewordElement(32628).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_1_CONTROL_F0_OFFSET_TO_CCU_VALUE,
									new FloatDoublewordElement(32630).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_1_CONTROL_Q_REF_OFFSET_TO_CCU_VALUE,
									new FloatDoublewordElement(32632).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_1_CONTROL_P_REF_OFFSET_TO_CCU_VALUE,
									new FloatDoublewordElement(32634).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_1_CONTROL_P_MAX_DISCHARGE,
									new FloatDoublewordElement(32636).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_1_CONTROL_P_MAX_CHARGE,
									new FloatDoublewordElement(32638).wordOrder(WordOrder.LSWMSW)) //
					),
					/*
					 * IPU 1 Mirror Control
					 */
					new FC3ReadRegistersTask(32944, Priority.LOW,
							m(GridConChannelId.INVERTER_1_CONTROL_DC_VOLTAGE_SETPOINT,
									new FloatDoublewordElement(32944).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_1_CONTROL_DC_CURRENT_SETPOINT,
									new FloatDoublewordElement(32946).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_1_CONTROL_U0_OFFSET_TO_CCU_VALUE,
									new FloatDoublewordElement(32948).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_1_CONTROL_F0_OFFSET_TO_CCU_VALUE,
									new FloatDoublewordElement(32950).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_1_CONTROL_Q_REF_OFFSET_TO_CCU_VALUE,
									new FloatDoublewordElement(32952).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_1_CONTROL_P_REF_OFFSET_TO_CCU_VALUE,
									new FloatDoublewordElement(32954).wordOrder(WordOrder.LSWMSW)) //
					));
		}

		if (inverterCount > 1) {
			/*
			 * At least 2 Inverters -> Add IPU 2
			 */
			result.addTasks(//
					/*
					 * IPU 2 State
					 */
					new FC3ReadRegistersTask(33200, Priority.LOW, //
							m(GridConChannelId.INVERTER_2_STATUS_STATE_MACHINE, new UnsignedWordElement(33200)), //
							m(GridConChannelId.INVERTER_2_STATUS_MCU, new UnsignedWordElement(33201)), //
							m(GridConChannelId.INVERTER_2_STATUS_FILTER_CURRENT,
									new FloatDoublewordElement(33202).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_2_STATUS_DC_LINK_POSITIVE_VOLTAGE,
									new FloatDoublewordElement(33204).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_2_STATUS_DC_LINK_NEGATIVE_VOLTAGE,
									new FloatDoublewordElement(33206).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_2_STATUS_DC_LINK_CURRENT,
									new FloatDoublewordElement(33208).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_2_STATUS_DC_LINK_ACTIVE_POWER,
									new FloatDoublewordElement(33210).wordOrder(WordOrder.LSWMSW),
									ElementToChannelConverter.INVERT), //
							m(GridConChannelId.INVERTER_2_STATUS_DC_LINK_UTILIZATION,
									new FloatDoublewordElement(33212).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_2_STATUS_FAN_SPEED_MAX,
									new UnsignedDoublewordElement(33214).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_2_STATUS_FAN_SPEED_MIN,
									new UnsignedDoublewordElement(33216).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_2_STATUS_TEMPERATURE_IGBT_MAX,
									new FloatDoublewordElement(33218).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_2_STATUS_TEMPERATURE_MCU_BOARD,
									new FloatDoublewordElement(33220).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_2_STATUS_TEMPERATURE_GRID_CHOKE,
									new FloatDoublewordElement(33222).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_2_STATUS_TEMPERATURE_INVERTER_CHOKE,
									new FloatDoublewordElement(33224).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_2_STATUS_RESERVE_1,
									new FloatDoublewordElement(33226).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_2_STATUS_RESERVE_2,
									new FloatDoublewordElement(33228).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_2_STATUS_RESERVE_3,
									new FloatDoublewordElement(33230).wordOrder(WordOrder.LSWMSW)) //
					),
					/*
					 * IPU 2 Control Parameters
					 */
					new FC16WriteRegistersTask(32656, //
							m(GridConChannelId.INVERTER_2_CONTROL_DC_VOLTAGE_SETPOINT,
									new FloatDoublewordElement(32656).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_2_CONTROL_DC_CURRENT_SETPOINT,
									new FloatDoublewordElement(32658).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_2_CONTROL_U0_OFFSET_TO_CCU_VALUE,
									new FloatDoublewordElement(32660).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_2_CONTROL_F0_OFFSET_TO_CCU_VALUE,
									new FloatDoublewordElement(32662).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_2_CONTROL_Q_REF_OFFSET_TO_CCU_VALUE,
									new FloatDoublewordElement(32664).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_2_CONTROL_P_REF_OFFSET_TO_CCU_VALUE,
									new FloatDoublewordElement(32666).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_2_CONTROL_P_MAX_DISCHARGE,
									new FloatDoublewordElement(32668).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_2_CONTROL_P_MAX_CHARGE,
									new FloatDoublewordElement(32670).wordOrder(WordOrder.LSWMSW)) //
					),
					/*
					 * IPU 2 Mirror Control
					 */
					new FC3ReadRegistersTask(32976, Priority.LOW,
							m(GridConChannelId.INVERTER_2_CONTROL_DC_VOLTAGE_SETPOINT,
									new FloatDoublewordElement(32976).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_2_CONTROL_DC_CURRENT_SETPOINT,
									new FloatDoublewordElement(32978).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_2_CONTROL_U0_OFFSET_TO_CCU_VALUE,
									new FloatDoublewordElement(32980).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_2_CONTROL_F0_OFFSET_TO_CCU_VALUE,
									new FloatDoublewordElement(32982).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_2_CONTROL_Q_REF_OFFSET_TO_CCU_VALUE,
									new FloatDoublewordElement(32984).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_2_CONTROL_P_REF_OFFSET_TO_CCU_VALUE,
									new FloatDoublewordElement(32986).wordOrder(WordOrder.LSWMSW)) //
					));
		}
		if (inverterCount > 2) {
			/*
			 * 3 Inverters -> Add IPU 3
			 */
			result.addTasks(//
					/*
					 * IPU 3 State
					 */
					new FC3ReadRegistersTask(33232, Priority.LOW, //
							m(GridConChannelId.INVERTER_3_STATUS_STATE_MACHINE, new UnsignedWordElement(33232)), //
							m(GridConChannelId.INVERTER_3_STATUS_MCU, new UnsignedWordElement(33233)), //
							m(GridConChannelId.INVERTER_3_STATUS_FILTER_CURRENT,
									new FloatDoublewordElement(33234).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_3_STATUS_DC_LINK_POSITIVE_VOLTAGE,
									new FloatDoublewordElement(33236).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_3_STATUS_DC_LINK_NEGATIVE_VOLTAGE,
									new FloatDoublewordElement(33238).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_3_STATUS_DC_LINK_CURRENT,
									new FloatDoublewordElement(33240).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_3_STATUS_DC_LINK_ACTIVE_POWER,
									new FloatDoublewordElement(33242).wordOrder(WordOrder.LSWMSW),
									ElementToChannelConverter.INVERT), //
							m(GridConChannelId.INVERTER_3_STATUS_DC_LINK_UTILIZATION,
									new FloatDoublewordElement(33244).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_3_STATUS_FAN_SPEED_MAX,
									new UnsignedDoublewordElement(33246).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_3_STATUS_FAN_SPEED_MIN,
									new UnsignedDoublewordElement(33248).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_3_STATUS_TEMPERATURE_IGBT_MAX,
									new FloatDoublewordElement(33250).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_3_STATUS_TEMPERATURE_MCU_BOARD,
									new FloatDoublewordElement(33252).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_3_STATUS_TEMPERATURE_GRID_CHOKE,
									new FloatDoublewordElement(33254).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_3_STATUS_TEMPERATURE_INVERTER_CHOKE,
									new FloatDoublewordElement(33256).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_3_STATUS_RESERVE_1,
									new FloatDoublewordElement(33258).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_3_STATUS_RESERVE_2,
									new FloatDoublewordElement(33260).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_3_STATUS_RESERVE_3,
									new FloatDoublewordElement(33262).wordOrder(WordOrder.LSWMSW)) //
					),
					/*
					 * IPU 3 Control Parameters
					 */
					new FC16WriteRegistersTask(32688, //
							m(GridConChannelId.INVERTER_3_CONTROL_DC_VOLTAGE_SETPOINT,
									new FloatDoublewordElement(32688).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_3_CONTROL_DC_CURRENT_SETPOINT,
									new FloatDoublewordElement(32690).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_3_CONTROL_U0_OFFSET_TO_CCU_VALUE,
									new FloatDoublewordElement(32692).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_3_CONTROL_F0_OFFSET_TO_CCU_VALUE,
									new FloatDoublewordElement(32694).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_3_CONTROL_Q_REF_OFFSET_TO_CCU_VALUE,
									new FloatDoublewordElement(32696).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_3_CONTROL_P_REF_OFFSET_TO_CCU_VALUE,
									new FloatDoublewordElement(32698).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_3_CONTROL_P_MAX_DISCHARGE,
									new FloatDoublewordElement(32700).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_3_CONTROL_P_MAX_CHARGE,
									new FloatDoublewordElement(32702).wordOrder(WordOrder.LSWMSW)) //
					),
					/*
					 * IPU 3 Mirror Control
					 */
					new FC3ReadRegistersTask(33008, Priority.LOW,
							m(GridConChannelId.INVERTER_3_CONTROL_DC_VOLTAGE_SETPOINT,
									new FloatDoublewordElement(33008).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_3_CONTROL_DC_CURRENT_SETPOINT,
									new FloatDoublewordElement(33010).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_3_CONTROL_U0_OFFSET_TO_CCU_VALUE,
									new FloatDoublewordElement(33012).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_3_CONTROL_F0_OFFSET_TO_CCU_VALUE,
									new FloatDoublewordElement(33014).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_3_CONTROL_Q_REF_OFFSET_TO_CCU_VALUE,
									new FloatDoublewordElement(33016).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.INVERTER_3_CONTROL_P_REF_OFFSET_TO_CCU_VALUE,
									new FloatDoublewordElement(33018).wordOrder(WordOrder.LSWMSW)) //
					));
		}

		{
			/*
			 * DCDC
			 * 
			 * if one inverter is used, dc dc converter is ipu2 ...
			 */
			int startAddressIpuControl = 32720; // == THREE
			int startAddressIpuControlMirror = 33040; // == THREE
			int startAddressIpuState = 33264; // == THREE
			int startAddressIpuDcdc = 33584; // == THREE
			switch (this.config.inverterCount()) {
			case ONE:
				startAddressIpuControl = 32656;
				startAddressIpuControlMirror = 32976;
				startAddressIpuState = 33200;
				startAddressIpuDcdc = 33520;
				break;
			case TWO:
				startAddressIpuControl = 32688;
				startAddressIpuControlMirror = 33008;
				startAddressIpuState = 33232;
				startAddressIpuDcdc = 33552;
				break;
			case THREE:
				// default
				break;
			}

			result.addTasks(//
					/*
					 * DCDC Control
					 */
					new FC16WriteRegistersTask(startAddressIpuControl, //
							m(GridConChannelId.DCDC_CONTROL_DC_VOLTAGE_SETPOINT,
									new FloatDoublewordElement(startAddressIpuControl).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.DCDC_CONTROL_WEIGHT_STRING_A,
									new FloatDoublewordElement(startAddressIpuControl + 2).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.DCDC_CONTROL_WEIGHT_STRING_B,
									new FloatDoublewordElement(startAddressIpuControl + 4).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.DCDC_CONTROL_WEIGHT_STRING_C,
									new FloatDoublewordElement(startAddressIpuControl + 6).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.DCDC_CONTROL_I_REF_STRING_A,
									new FloatDoublewordElement(startAddressIpuControl + 8).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.DCDC_CONTROL_I_REF_STRING_B,
									new FloatDoublewordElement(startAddressIpuControl + 10)
											.wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.DCDC_CONTROL_I_REF_STRING_C,
									new FloatDoublewordElement(startAddressIpuControl + 12)
											.wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.DCDC_CONTROL_STRING_CONTROL_MODE,
									new FloatDoublewordElement(startAddressIpuControl + 14).wordOrder(WordOrder.LSWMSW)) //
					),
					/*
					 * DCDC Control Mirror
					 */
					new FC3ReadRegistersTask(startAddressIpuControlMirror, Priority.LOW,
							m(GridConChannelId.DCDC_CONTROL_DC_VOLTAGE_SETPOINT,
									new FloatDoublewordElement(startAddressIpuControlMirror)
											.wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.DCDC_CONTROL_WEIGHT_STRING_A,
									new FloatDoublewordElement(startAddressIpuControlMirror + 2)
											.wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.DCDC_CONTROL_WEIGHT_STRING_B,
									new FloatDoublewordElement(startAddressIpuControlMirror + 4)
											.wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.DCDC_CONTROL_WEIGHT_STRING_C,
									new FloatDoublewordElement(startAddressIpuControlMirror + 6)
											.wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.DCDC_CONTROL_I_REF_STRING_A,
									new FloatDoublewordElement(startAddressIpuControlMirror + 8)
											.wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.DCDC_CONTROL_I_REF_STRING_B,
									new FloatDoublewordElement(startAddressIpuControlMirror + 10)
											.wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.DCDC_CONTROL_I_REF_STRING_C,
									new FloatDoublewordElement(startAddressIpuControlMirror + 12)
											.wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.DCDC_CONTROL_STRING_CONTROL_MODE,
									new FloatDoublewordElement(startAddressIpuControlMirror + 14)
											.wordOrder(WordOrder.LSWMSW)) //
					),
					/*
					 * DCDC State
					 */
					new FC3ReadRegistersTask(startAddressIpuState, Priority.LOW, // // IPU 4 state
							m(GridConChannelId.DCDC_STATUS_STATE_MACHINE,
									new UnsignedWordElement(startAddressIpuState)), //
							m(GridConChannelId.DCDC_STATUS_MCU, new UnsignedWordElement(startAddressIpuState + 1)), //
							m(GridConChannelId.DCDC_STATUS_FILTER_CURRENT,
									new FloatDoublewordElement(startAddressIpuState + 2).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.DCDC_STATUS_DC_LINK_POSITIVE_VOLTAGE,
									new FloatDoublewordElement(startAddressIpuState + 4).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.DCDC_STATUS_DC_LINK_NEGATIVE_VOLTAGE,
									new FloatDoublewordElement(startAddressIpuState + 6).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.DCDC_STATUS_DC_LINK_CURRENT,
									new FloatDoublewordElement(startAddressIpuState + 8).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.DCDC_STATUS_DC_LINK_ACTIVE_POWER,
									new FloatDoublewordElement(startAddressIpuState + 10).wordOrder(WordOrder.LSWMSW),
									ElementToChannelConverter.INVERT), //
							m(GridConChannelId.DCDC_STATUS_DC_LINK_UTILIZATION,
									new FloatDoublewordElement(startAddressIpuState + 12).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.DCDC_STATUS_FAN_SPEED_MAX,
									new UnsignedDoublewordElement(startAddressIpuState + 14)
											.wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.DCDC_STATUS_FAN_SPEED_MIN,
									new UnsignedDoublewordElement(startAddressIpuState + 16)
											.wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.DCDC_STATUS_TEMPERATURE_IGBT_MAX,
									new FloatDoublewordElement(startAddressIpuState + 18).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.DCDC_STATUS_TEMPERATURE_MCU_BOARD,
									new FloatDoublewordElement(startAddressIpuState + 20).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.DCDC_STATUS_TEMPERATURE_GRID_CHOKE,
									new FloatDoublewordElement(startAddressIpuState + 22).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.DCDC_STATUS_TEMPERATURE_INVERTER_CHOKE,
									new FloatDoublewordElement(startAddressIpuState + 24).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.DCDC_STATUS_RESERVE_1,
									new FloatDoublewordElement(startAddressIpuState + 26).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.DCDC_STATUS_RESERVE_2,
									new FloatDoublewordElement(startAddressIpuState + 28).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.DCDC_STATUS_RESERVE_3,
									new FloatDoublewordElement(startAddressIpuState + 30).wordOrder(WordOrder.LSWMSW)) //
					),
					/*
					 * DCDC Measurements
					 */
					new FC3ReadRegistersTask(startAddressIpuDcdc, Priority.LOW, // IPU 4 measurements
							m(GridConChannelId.DCDC_MEASUREMENTS_VOLTAGE_STRING_A,
									new FloatDoublewordElement(startAddressIpuDcdc).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.DCDC_MEASUREMENTS_VOLTAGE_STRING_B,
									new FloatDoublewordElement(startAddressIpuDcdc + 2).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.DCDC_MEASUREMENTS_VOLTAGE_STRING_C,
									new FloatDoublewordElement(startAddressIpuDcdc + 4).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.DCDC_MEASUREMENTS_CURRENT_STRING_A,
									new FloatDoublewordElement(startAddressIpuDcdc + 6).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.DCDC_MEASUREMENTS_CURRENT_STRING_B,
									new FloatDoublewordElement(startAddressIpuDcdc + 8).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.DCDC_MEASUREMENTS_CURRENT_STRING_C,
									new FloatDoublewordElement(startAddressIpuDcdc + 10).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.DCDC_MEASUREMENTS_POWER_STRING_A,
									new FloatDoublewordElement(startAddressIpuDcdc + 12).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.DCDC_MEASUREMENTS_POWER_STRING_B,
									new FloatDoublewordElement(startAddressIpuDcdc + 14).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.DCDC_MEASUREMENTS_POWER_STRING_C,
									new FloatDoublewordElement(startAddressIpuDcdc + 16).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.DCDC_MEASUREMENTS_UTILIZATION_STRING_A,
									new FloatDoublewordElement(startAddressIpuDcdc + 18).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.DCDC_MEASUREMENTS_UTILIZATION_STRING_B,
									new FloatDoublewordElement(startAddressIpuDcdc + 20).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.DCDC_MEASUREMENTS_UTILIZATION_STRING_C,
									new FloatDoublewordElement(startAddressIpuDcdc + 22).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.DCDC_MEASUREMENTS_ACCUMULATED_SUM_DC_CURRENT,
									new FloatDoublewordElement(startAddressIpuDcdc + 24).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.DCDC_MEASUREMENTS_ACCUMULATED_DC_UTILIZATION,
									new FloatDoublewordElement(startAddressIpuDcdc + 26).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.DCDC_MEASUREMENTS_RESERVE_1,
									new FloatDoublewordElement(startAddressIpuDcdc + 28).wordOrder(WordOrder.LSWMSW)), //
							m(GridConChannelId.DCDC_MEASUREMENTS_RESERVE_2,
									new FloatDoublewordElement(startAddressIpuDcdc + 30).wordOrder(WordOrder.LSWMSW)) //
					));
		}

		// Calculate Total Active Power
		FloatReadChannel ap1 = this.channel(GridConChannelId.INVERTER_1_STATUS_DC_LINK_ACTIVE_POWER);
		FloatReadChannel ap2 = this.channel(GridConChannelId.INVERTER_2_STATUS_DC_LINK_ACTIVE_POWER);
		FloatReadChannel ap3 = this.channel(GridConChannelId.INVERTER_3_STATUS_DC_LINK_ACTIVE_POWER);
		final Consumer<Value<Float>> calculateActivePower = ignoreValue -> {
			float ipu1 = ap1.getNextValue().orElse(0f);
			float ipu2 = ap2.getNextValue().orElse(0f);
			float ipu3 = ap3.getNextValue().orElse(0f);
			this.getActivePower().setNextValue(ipu1 + ipu2 + ipu3);
		};
		ap1.onSetNextValue(calculateActivePower);
		ap2.onSetNextValue(calculateActivePower);
		ap3.onSetNextValue(calculateActivePower);

		return result;
	}

	private void setOutputSyncDeviceBridge(boolean value) throws IllegalArgumentException, OpenemsNamedException {
		BooleanWriteChannel outputSyncDeviceBridge = this.componentManager
				.getChannel(ChannelAddress.fromString(this.config.outputSyncDeviceBridge()));
		this.setOutput(outputSyncDeviceBridge, value);
	}

	/**
	 * Helper function to switch an output if it was not switched before.
	 *
	 * @param value true to switch ON, false to switch ON
	 */
	private void setOutput(BooleanWriteChannel channel, boolean value) {
		Optional<Boolean> currentValueOpt = channel.value().asOptional();
		if (!currentValueOpt.isPresent() || currentValueOpt.get() != value) {
			log.info("Set output [" + channel.address() + "] " + (value ? "ON" : "OFF") + ".");
			try {
				channel.setNextWriteValue(value);
			} catch (OpenemsNamedException e) {
				this.logError(this.log, "Unable to set output: [" + channel.address() + "] " + e.getMessage());
			}
		}
	}

	@Override
	public ModbusSlaveTable getModbusSlaveTable() {
		return new ModbusSlaveTable( //
				OpenemsComponent.getModbusSlaveNatureTable(), //
				SymmetricEss.getModbusSlaveNatureTable(), //
				ManagedSymmetricEss.getModbusSlaveNatureTable(), //
				ModbusSlaveNatureTable.of(GridconPCS.class, 300) //
						.build());
	}
}
