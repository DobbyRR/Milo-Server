package com.synclab.miloserver.machine.mainFactory.cylindricalLine.electrodeUnit2nd;

import com.synclab.miloserver.opcua.MultiMachineNameSpace;
import com.synclab.miloserver.opcua.UnitLogic;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;

import java.util.concurrent.ThreadLocalRandom;

public class ElectrodeUnit02 extends UnitLogic {

    private double mixPhase = Math.PI / 6;

    private static final double[] STAGE_DURATIONS_SEC = {2.0, 3.0, 3.0, 2.0, 2.0};
    private static final double TOTAL_CYCLE_TIME_SEC = 12.0;
    private static final double TIME_ACCELERATION = 5.0;

    private int stageIndex = 0;
    private double stageElapsed = 0.0;
    private double cycleElapsed = 0.0;
    private double totalElapsedSeconds = 0.0;
    private int processedSerialCount = 0;
    private boolean currentSerialOkFlag = true;
    private int currentNgType = 0;

    private double viscosityCp = 0.0;
    private double coatingThicknessUm = 0.0;
    private double ovenTempC = 0.0;
    private double calenderPressureMpa = 0.0;
    private double slitWidthDevUm = 0.0;

    /**
     * Electrode NG Type codes (1~4)
     * 1 - 슬러리 점도 이상
     * 2 - 코팅 두께 불량
     * 3 - 오븐 온도 이상
     * 4 - 슬리팅 정밀도 불량
     */
    private static final String[] NG_TYPE_NAMES = {
            "슬러리 점도 이상",
            "코팅 두께 불량",
            "오븐 온도 이상",
            "슬리팅 정밀도 불량"
    };
    private static final class NgType {
        static final int SLURRY_VISCOSITY = 1;
        static final int COATING_THICKNESS = 2;
        static final int OVEN_TEMPERATURE = 3;
        static final int SLITTING_ACCURACY = 4;

        private NgType() {}
    }

    public ElectrodeUnit02(String name, UaFolderNode folder, MultiMachineNameSpace ns) {
        super(name, folder);
        this.unitType = "ELECTRODE";
        this.lineId = "CylindricalLine";
        this.machineNo = 2;
        this.equipmentCode = "F1-CL1-EU002";
        this.processId = "Electrode";
        configureEnergyProfile(1.1, 0.12, 11.5, 1.3);
        this.defaultPpm = 92;
        setUnitsPerCycle(1);

        configureAlarms();
        setupCommonTelemetry(ns);
        registerNgTypeNames(NG_TYPE_NAMES);
        setupVariables(ns);
    }

    @Override
    public void setupVariables(MultiMachineNameSpace ns) {
        telemetryNodes.put("mix_viscosity", ns.addVariableNode(machineFolder, name + ".mix_viscosity", 0.0));
        telemetryNodes.put("slurry_temperature", ns.addVariableNode(machineFolder, name + ".slurry_temperature", 25.0));
        telemetryNodes.put("coating_thickness", ns.addVariableNode(machineFolder, name + ".coating_thickness", 0.0));
        telemetryNodes.put("oven_temperature", ns.addVariableNode(machineFolder, name + ".oven_temperature", 0.0));
        telemetryNodes.put("calender_pressure", ns.addVariableNode(machineFolder, name + ".calender_pressure", 0.0));
        telemetryNodes.put("slitting_accuracy", ns.addVariableNode(machineFolder, name + ".slitting_accuracy", 0.0));

        telemetryNodes.put("viscosity_cP", ns.addVariableNode(machineFolder, name + ".viscosity_cP", 0.0));
        telemetryNodes.put("coat_thickness_um", ns.addVariableNode(machineFolder, name + ".coat_thickness_um", 0.0));
        telemetryNodes.put("oven_temp_c", ns.addVariableNode(machineFolder, name + ".oven_temp_c", 0.0));
        telemetryNodes.put("calender_pressure_MPa", ns.addVariableNode(machineFolder, name + ".calender_pressure_MPa", 0.0));
        telemetryNodes.put("slit_width_dev_um", ns.addVariableNode(machineFolder, name + ".slit_width_dev_um", 0.0));
        telemetryNodes.put("current_serial", ns.addVariableNode(machineFolder, name + ".current_serial", ""));
        telemetryNodes.put("serial_ok", ns.addVariableNode(machineFolder, name + ".serial_ok", true));
        telemetryNodes.put("ng_type", ns.addVariableNode(machineFolder, name + ".ng_type", 0));
        telemetryNodes.put("cycle_time_sec", ns.addVariableNode(machineFolder, name + ".cycle_time_sec", TOTAL_CYCLE_TIME_SEC));
        telemetryNodes.put("t_in_cycle_sec", ns.addVariableNode(machineFolder, name + ".t_in_cycle_sec", 0.0));
        telemetryNodes.put("processed_count", ns.addVariableNode(machineFolder, name + ".processed_count", 0));
        telemetryNodes.put("good_count", ns.addVariableNode(machineFolder, name + ".good_count", 0));
        telemetryNodes.put("ng_count", ns.addVariableNode(machineFolder, name + ".ng_count", 0));
        telemetryNodes.put("throughput_upm", ns.addVariableNode(machineFolder, name + ".throughput_upm", 0.0));
    }

    @Override
    public void onCommand(MultiMachineNameSpace ns, String command) {
        if (!handleCommonCommand(ns, command)) {
            System.err.printf("[ElectrodeUnit02] Unsupported command '%s'%n", command);
        }
    }

    @Override
    public void simulateStep(MultiMachineNameSpace ns) {
        processAlarms(ns);
        switch (state) {
            case "IDLE":
                simulateIdle(ns);
                break;
            case "STARTING":
                if (timeInState(2000)) {
                    updateOrderStatus(ns, "EXECUTE");
                    changeState(ns, "EXECUTE");
                }
                break;
            case "EXECUTE":
                handleExecute(ns);
                break;
            case "COMPLETING":
                updateTelemetry(ns, "calender_pressure", 0.0);
                if (timeInState(2000)) {
                    onOrderCompleted(ns);
                }
                break;
            case "COMPLETE":
                break;
            case "RESETTING":
                if (!awaitingMesAck && timeInState(1000)) {
                    resetOrderState(ns);
                    changeState(ns, "IDLE");
                }
                break;
            case "STOPPING":
                updateTelemetry(ns, "alarm_code", "STOP_EU");
                updateTelemetry(ns, "alarm_level", "INFO");
                if (timeInState(1000)) {
                    changeState(ns, "IDLE");
                }
                break;
            case "HOLD":
            case "SUSPEND":
                applyIdleDrift(ns);
                break;
        }
    }

    private void simulateIdle(MultiMachineNameSpace ns) {
        mixPhase += 0.1;
        double viscosityIdle = 1095 + Math.sin(mixPhase) * 38 + (Math.random() - 0.5) * 12;
        updateTelemetry(ns, "mix_viscosity", viscosityIdle);
        updateTelemetry(ns, "slurry_temperature", 25 + (Math.random() - 0.5) * 0.6);
        updateTelemetry(ns, "oven_temperature", 154 + (Math.random() - 0.5) * 1.5);
        applyIdleDrift(ns);
    }

    private void handleExecute(MultiMachineNameSpace ns) {
        applyOperatingEnergy(ns);
        double deltaSeconds = TIME_ACCELERATION;
        totalElapsedSeconds += deltaSeconds;
        cycleElapsed += deltaSeconds;

        if (!hasMoreSerials()) {
            if (!"IDLE".equals(state)) {
                changeState(ns, "IDLE");
            }
            return;
        }

        if (!prepareCurrentSerial(ns)) {
            return;
        }

        stageElapsed += deltaSeconds;
        while (stageElapsed >= STAGE_DURATIONS_SEC[stageIndex]) {
            stageElapsed -= STAGE_DURATIONS_SEC[stageIndex];
            stageIndex++;
            if (stageIndex >= STAGE_DURATIONS_SEC.length) {
                concludeSerialCycle(ns);
                stageIndex = 0;
                stageElapsed = 0.0;
                break;
            }
        }
        updateTelemetry(ns, "t_in_cycle_sec", Math.min(cycleElapsed, TOTAL_CYCLE_TIME_SEC));
    }

    private boolean prepareCurrentSerial(MultiMachineNameSpace ns) {
        if (activeSerial != null && !activeSerial.isEmpty()) {
            return true;
        }
        String nextSerial = acquireNextSerial(ns);
        if (nextSerial.isEmpty()) {
            updateTelemetry(ns, "serial_ok", true);
            updateTelemetry(ns, "current_serial", "");
            updateTelemetry(ns, "ng_type", 0);
            return false;
        }
        currentSerialOkFlag = true;
        currentNgType = 0;
        updateTelemetry(ns, "current_serial", nextSerial);
        updateTelemetry(ns, "serial_ok", true);
        updateTelemetry(ns, "ng_type", 0);
        return true;
    }

    private void concludeSerialCycle(MultiMachineNameSpace ns) {
        sampleProcessMetrics();
        updateMetricTelemetry(ns);
        cycleElapsed = 0.0;

        boolean viscosityOk = viscosityCp >= 900 && viscosityCp <= 1300;
        boolean thicknessOk = coatingThicknessUm >= 83 && coatingThicknessUm <= 92;
        boolean ovenOk = ovenTempC >= 120;
        boolean pressureOk = calenderPressureMpa >= 90 && calenderPressureMpa <= 110;
        boolean slitOk = Math.abs(slitWidthDevUm) <= 10;

        boolean serialOk = true;
        int ngType = 0;
        if (!viscosityOk) {
            serialOk = false;
            ngType = NgType.SLURRY_VISCOSITY;
        } else if (!thicknessOk) {
            serialOk = false;
            ngType = NgType.COATING_THICKNESS;
        } else if (!ovenOk) {
            serialOk = false;
            ngType = NgType.OVEN_TEMPERATURE;
        } else if (!pressureOk || !slitOk) {
            serialOk = false;
            ngType = NgType.SLITTING_ACCURACY;
        }

        processedSerialCount++;
        updateProducedQuantity(ns, producedQuantity + 1);

        if (serialOk) {
            completeActiveSerialOk(ns);
            updateQualityCounts(ns, okCount + 1, ngCount);
            currentSerialOkFlag = true;
            currentNgType = 0;
        } else {
            completeActiveSerialNg(ns, ngType);
            updateQualityCounts(ns, okCount, ngCount + 1);
            currentSerialOkFlag = false;
            currentNgType = ngType;
        }

        updateTelemetry(ns, "serial_ok", currentSerialOkFlag);
        updateTelemetry(ns, "ng_type", currentNgType);
        updateProcessCountersTelemetry(ns);
        updateThroughputTelemetry(ns);

        String nextSerial = acquireNextSerial(ns);
        if (!nextSerial.isEmpty()) {
            updateTelemetry(ns, "current_serial", nextSerial);
            updateTelemetry(ns, "serial_ok", true);
            updateTelemetry(ns, "ng_type", 0);
        } else {
            updateTelemetry(ns, "current_serial", "");
        }
    }

    private void configureAlarms() {
        AlarmDefinition coaterStop = registerAlarm(
                "EU02_COATER_STOP",
                "코터 구동 정지",
                AlarmSeverity.FAULT,
                AlarmCause.INTERNAL
        );
        AlarmDefinition dryerCoolingFail = registerAlarm(
                "EU02_DRYER_COOLING",
                "건조 존 냉각 이상",
                AlarmSeverity.EMERGENCY,
                AlarmCause.INTERNAL
        );
        AlarmDefinition bufferEmpty = registerAlarm(
                "EU02_BUFFER_EMPTY",
                "슬러리 버퍼 고갈",
                AlarmSeverity.WARNING,
                AlarmCause.EXTERNAL
        );

        registerAlarmScenario(coaterStop, 0.00015, 7000, 15000);
        registerAlarmScenario(dryerCoolingFail, 0.00010, 9000, 20000);
        registerAlarmScenario(bufferEmpty, 0.00018, 4000, 9000);
    }

    private void sampleProcessMetrics() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        mixPhase += 0.18;
        viscosityCp = randomWithin(rnd, 1090.0, 0.04);
        coatingThicknessUm = randomWithin(rnd, 88.2, 0.045);
        ovenTempC = randomWithin(rnd, 165.0, 0.03);
        calenderPressureMpa = randomWithin(rnd, 100.5, 0.035);
        slitWidthDevUm = (rnd.nextDouble() - 0.5) * 8.0;
    }

    private double randomWithin(ThreadLocalRandom rnd, double center, double pctSpread) {
        double spread = center * pctSpread;
        return center + (rnd.nextDouble() * 2.0 - 1.0) * spread;
    }

    private void updateMetricTelemetry(MultiMachineNameSpace ns) {
        updateTelemetry(ns, "viscosity_cP", viscosityCp);
        updateTelemetry(ns, "coat_thickness_um", coatingThicknessUm);
        updateTelemetry(ns, "oven_temp_c", ovenTempC);
        updateTelemetry(ns, "calender_pressure_MPa", calenderPressureMpa);
        updateTelemetry(ns, "slit_width_dev_um", slitWidthDevUm);
        updateTelemetry(ns, "mix_viscosity", viscosityCp);
        updateTelemetry(ns, "coating_thickness", coatingThicknessUm);
        updateTelemetry(ns, "oven_temperature", ovenTempC);
        updateTelemetry(ns, "calender_pressure", calenderPressureMpa);
        updateTelemetry(ns, "slitting_accuracy", Math.abs(slitWidthDevUm));
    }

    private void updateProcessCountersTelemetry(MultiMachineNameSpace ns) {
        updateTelemetry(ns, "processed_count", processedSerialCount);
        updateTelemetry(ns, "good_count", okCount);
        updateTelemetry(ns, "ng_count", ngCount);
    }

    private void updateThroughputTelemetry(MultiMachineNameSpace ns) {
        double minutes = totalElapsedSeconds / 60.0;
        double throughput = minutes <= 0.0 ? 0.0 : okCount / minutes;
        updateTelemetry(ns, "throughput_upm", throughput);
        updateTelemetry(ns, "cycle_time_sec", TOTAL_CYCLE_TIME_SEC);
    }

    @Override
    protected void resetOrderState(MultiMachineNameSpace ns) {
        super.resetOrderState(ns);
        stageIndex = 0;
        stageElapsed = 0.0;
        totalElapsedSeconds = 0.0;
        processedSerialCount = 0;
        currentSerialOkFlag = true;
        currentNgType = 0;
        updateTelemetry(ns, "current_serial", "");
        updateTelemetry(ns, "serial_ok", true);
        updateTelemetry(ns, "ng_type", 0);
        updateTelemetry(ns, "processed_count", 0);
        updateTelemetry(ns, "good_count", 0);
        updateTelemetry(ns, "ng_count", 0);
        updateTelemetry(ns, "throughput_upm", 0.0);
    }
}
