package com.synclab.miloserver.machine.mainFactory.cylindricalLine.cellCleanUnit6th;

import com.synclab.miloserver.opcua.MultiMachineNameSpace;
import com.synclab.miloserver.opcua.UnitLogic;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;

import java.util.concurrent.ThreadLocalRandom;

public class CellCleaner01 extends UnitLogic {

    private static final double[] STAGE_DURATIONS_SEC = {2.0, 2.0, 2.0, 2.0};
    private static final double TOTAL_CYCLE_TIME_SEC = 8.0;
    private static final double TIME_ACCELERATION = 10.0;

    private int stageIndex = 0;
    private double stageElapsed = 0.0;
    private double cycleElapsed = 0.0;
    private double totalElapsedSeconds = 0.0;
    private int processedSerialCount = 0;
    private boolean currentSerialOkFlag = true;
    private int currentNgType = 0;

    private double ultrasonicPowerW = 0.0;
    private double residualMoisturePpm = 0.0;
    private boolean surfaceDefectDetected = false;
    private double dryingTemperatureC = 0.0;
    private double cleanlinessScore = 0.0;

    /**
     * Cell Cleaner NG Type codes (1~4)
     * 1 - 잔류 수분 과다
     * 2 - 초음파 출력 부족
     * 3 - 표면 결함 검출
     * 4 - 건조 온도 이상
     */
    private static final String[] NG_TYPE_NAMES = {
            "잔류 수분 과다",
            "초음파 출력 부족",
            "표면 결함 검출",
            "건조 온도 이상"
    };
    private static final class NgType {
        static final int RESIDUAL_MOISTURE = 1;
        static final int ULTRASONIC_POWER = 2;
        static final int SURFACE_DEFECT = 3;
        static final int DRYING_TEMPERATURE = 4;

        private NgType() {}
    }

    public CellCleaner01(String name,
                         String lineId,
                         String equipmentCode,
                         UaFolderNode folder,
                         MultiMachineNameSpace ns) {
        super(name, folder);
        this.unitType = "CELL_CLEAN";
        this.lineId = lineId;
        this.machineNo = 6;
        this.equipmentCode = equipmentCode;
        this.processId = "CellCleaning";
        this.defaultPpm = 55;
        setUnitsPerCycle(1);
        configureEnergyProfile(0.5, 0.07, 5.0, 0.6);

        configureAlarms();
        setupCommonTelemetry(ns);
        registerNgTypeNames(NG_TYPE_NAMES);
        setupVariables(ns);
    }

    @Override
    public void setupVariables(MultiMachineNameSpace ns) {
        telemetryNodes.put("ultrasonic_power", ns.addVariableNode(machineFolder, name + ".ultrasonic_power", 0.0));
        telemetryNodes.put("residual_moisture", ns.addVariableNode(machineFolder, name + ".residual_moisture", 0.0));
        telemetryNodes.put("surface_defects", ns.addVariableNode(machineFolder, name + ".surface_defects", 0));
        telemetryNodes.put("drying_temperature", ns.addVariableNode(machineFolder, name + ".drying_temperature", 0.0));
        telemetryNodes.put("cleanliness_score", ns.addVariableNode(machineFolder, name + ".cleanliness_score", 0.0));
        telemetryNodes.put("current_serial", ns.addVariableNode(machineFolder, name + ".current_serial", ""));
        telemetryNodes.put("serial_ok", ns.addVariableNode(machineFolder, name + ".serial_ok", true));
        telemetryNodes.put("ng_type", ns.addVariableNode(machineFolder, name + ".ng_type", 0));
        telemetryNodes.put("cycle_time_sec", ns.addVariableNode(machineFolder, name + ".cycle_time_sec", TOTAL_CYCLE_TIME_SEC));
        telemetryNodes.put("t_in_cycle_sec", ns.addVariableNode(machineFolder, name + ".t_in_cycle_sec", 0.0));
        telemetryNodes.put("processed_count", ns.addVariableNode(machineFolder, name + ".processed_count", 0));
        telemetryNodes.put("good_count", ns.addVariableNode(machineFolder, name + ".good_count", 0));
        telemetryNodes.put("ng_count", ns.addVariableNode(machineFolder, name + ".ng_count", 0));
    }

    @Override
    public void onCommand(MultiMachineNameSpace ns, String command) {
        if (!handleCommonCommand(ns, command)) {
            System.err.printf("[CellCleaner01] Unsupported command '%s'%n", command);
        }
    }

    @Override
    public void simulateStep(MultiMachineNameSpace ns) {
        processAlarms(ns);
        switch (state) {
            case "IDLE":
                applyIdleDrift(ns);
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
                updateTelemetry(ns, "alarm_code", "STOP_CC");
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

    private void configureAlarms() {
        AlarmDefinition plasmaFault = registerAlarm(
                "CC01_PLASMA_FAULT",
                "플라즈마 파워 이상",
                AlarmSeverity.FAULT,
                AlarmCause.INTERNAL
        );
        AlarmDefinition heaterOver = registerAlarm(
                "CC01_HEATER_OVER",
                "건조 히터 과온",
                AlarmSeverity.EMERGENCY,
                AlarmCause.INTERNAL
        );
        AlarmDefinition supplyWait = registerAlarm(
                "CC01_SUPPLY_WAIT",
                "상위 공정 대기 상태",
                AlarmSeverity.WARNING,
                AlarmCause.EXTERNAL
        );

        registerAlarmScenario(plasmaFault, 0.00016, 6000, 12000);
        registerAlarmScenario(heaterOver, 0.00010, 8000, 16000);
        registerAlarmScenario(supplyWait, 0.00018, 4000, 9000);
    }

    private boolean prepareCurrentSerial(MultiMachineNameSpace ns) {
        if (activeSerial != null && !activeSerial.isEmpty()) {
            return true;
        }
        String nextSerial = acquireNextSerial(ns);
        if (nextSerial.isEmpty()) {
            updateTelemetry(ns, "current_serial", "");
            updateTelemetry(ns, "serial_ok", true);
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
        cycleElapsed = 0.0;
        sampleProcessMetrics();
        updateMetricTelemetry(ns);

        boolean powerOk = ultrasonicPowerW >= 115 && ultrasonicPowerW <= 125;
        boolean moistureOk = residualMoisturePpm <= 3.0;
        boolean tempOk = dryingTemperatureC >= 53.0 && dryingTemperatureC <= 57.0;
        boolean cleanlinessOk = cleanlinessScore >= 88.0;

        boolean serialOk = true;
        int ngType = 0;
        if (!moistureOk) {
            serialOk = false;
            ngType = NgType.RESIDUAL_MOISTURE;
        } else if (!powerOk) {
            serialOk = false;
            ngType = NgType.ULTRASONIC_POWER;
        } else if (surfaceDefectDetected) {
            serialOk = false;
            ngType = NgType.SURFACE_DEFECT;
        } else if (!tempOk || !cleanlinessOk) {
            serialOk = false;
            ngType = NgType.DRYING_TEMPERATURE;
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

        updateTelemetry(ns, "surface_defects", surfaceDefectDetected ? 1 : 0);
        updateTelemetry(ns, "serial_ok", currentSerialOkFlag);
        updateTelemetry(ns, "ng_type", currentNgType);
        updateProcessCountersTelemetry(ns);

        String nextSerial = acquireNextSerial(ns);
        if (!nextSerial.isEmpty()) {
            updateTelemetry(ns, "current_serial", nextSerial);
            updateTelemetry(ns, "serial_ok", true);
            updateTelemetry(ns, "ng_type", 0);
        } else {
            updateTelemetry(ns, "current_serial", "");
        }
    }

    private void sampleProcessMetrics() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        ultrasonicPowerW = 120.5 + (rnd.nextDouble() - 0.5) * 4.0;
        residualMoisturePpm = Math.max(0.2, 2.0 + (rnd.nextDouble() - 0.5) * 1.0);
        surfaceDefectDetected = rnd.nextDouble() > 0.995;
        dryingTemperatureC = 55.0 + (rnd.nextDouble() - 0.5) * 1.2;
        cleanlinessScore = 93.0 + (rnd.nextDouble() - 0.5) * 2.0;
    }

    private void updateMetricTelemetry(MultiMachineNameSpace ns) {
        updateTelemetry(ns, "ultrasonic_power", ultrasonicPowerW);
        updateTelemetry(ns, "residual_moisture", residualMoisturePpm);
        updateTelemetry(ns, "surface_defects", surfaceDefectDetected ? 1 : 0);
        updateTelemetry(ns, "drying_temperature", dryingTemperatureC);
        updateTelemetry(ns, "cleanliness_score", cleanlinessScore);
    }

    private void updateProcessCountersTelemetry(MultiMachineNameSpace ns) {
        updateTelemetry(ns, "processed_count", processedSerialCount);
        updateTelemetry(ns, "good_count", okCount);
        updateTelemetry(ns, "ng_count", ngCount);
    }

    @Override
    protected void resetOrderState(MultiMachineNameSpace ns) {
        super.resetOrderState(ns);
        stageIndex = 0;
        stageElapsed = 0.0;
        cycleElapsed = 0.0;
        totalElapsedSeconds = 0.0;
        processedSerialCount = 0;
        currentSerialOkFlag = true;
        currentNgType = 0;
        ultrasonicPowerW = 0.0;
        residualMoisturePpm = 0.0;
        surfaceDefectDetected = false;
        dryingTemperatureC = 0.0;
        cleanlinessScore = 0.0;
        updateTelemetry(ns, "current_serial", "");
        updateTelemetry(ns, "serial_ok", true);
        updateTelemetry(ns, "ng_type", 0);
        updateTelemetry(ns, "processed_count", 0);
        updateTelemetry(ns, "good_count", 0);
        updateTelemetry(ns, "ng_count", 0);
        updateTelemetry(ns, "t_in_cycle_sec", 0.0);
        updateMetricTelemetry(ns);
    }
}
