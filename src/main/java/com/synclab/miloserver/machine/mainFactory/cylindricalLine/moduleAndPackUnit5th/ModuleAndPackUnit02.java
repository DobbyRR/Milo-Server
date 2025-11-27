package com.synclab.miloserver.machine.mainFactory.cylindricalLine.moduleAndPackUnit5th;

import com.synclab.miloserver.opcua.MultiMachineNameSpace;
import com.synclab.miloserver.opcua.UnitLogic;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;

import java.util.concurrent.ThreadLocalRandom;

public class ModuleAndPackUnit02 extends UnitLogic {

    private static final double[] STAGE_DURATIONS_SEC = {3.0, 3.0, 3.0, 3.0};
    private static final double TOTAL_CYCLE_TIME_SEC = 12.0;
    private static final double TIME_ACCELERATION = 4.0;

    private int stageIndex = 0;
    private double stageElapsed = 0.0;
    private double cycleElapsed = 0.0;
    private double totalElapsedSeconds = 0.0;
    private int processedSerialCount = 0;
    private boolean currentSerialOkFlag = true;
    private int currentNgType = 0;

    private double cellAlignmentMm = 0.0;
    private double moduleResistanceMOhm = 0.0;
    private boolean bmsHealthy = true;
    private double weldResistanceMOhm = 0.0;
    private double torqueNm = 0.0;

    /**
     * Module & Pack NG Type codes (1~4)
     * 1 - 셀 정렬 불량
     * 2 - 모듈 저항 불량
     * 3 - 용접 저항 불량
     * 4 - 체결 토크 불량
     */
    private static final String[] NG_TYPE_NAMES = {
            "셀 정렬 불량",
            "모듈 저항 불량",
            "용접 저항 불량",
            "체결 토크 불량"
    };
    private static final class NgType {
        static final int CELL_ALIGNMENT = 1;
        static final int MODULE_RESISTANCE = 2;
        static final int WELD_RESISTANCE = 3;
        static final int TORQUE_OUT_OF_SPEC = 4;

        private NgType() {}
    }

    public ModuleAndPackUnit02(String name,
                               String lineId,
                               String equipmentPrefix,
                               UaFolderNode folder,
                               MultiMachineNameSpace ns) {
        super(name, folder);
        this.unitType = "MODULE_PACK";
        this.lineId = lineId;
        this.machineNo = 5;
        this.equipmentCode = equipmentCode;
        this.processId = "ModulePack";
        this.defaultPpm = 62;
        setUnitsPerCycle(1);
        configureEnergyProfile(0.82, 0.1, 8.2, 1.05);

        configureAlarms();
        setupCommonTelemetry(ns);
        registerNgTypeNames(NG_TYPE_NAMES);
        setupVariables(ns);
    }

    @Override
    public void setupVariables(MultiMachineNameSpace ns) {
        telemetryNodes.put("cell_alignment", ns.addVariableNode(machineFolder, name + ".cell_alignment", 0.0));
        telemetryNodes.put("module_resistance", ns.addVariableNode(machineFolder, name + ".module_resistance", 0.0));
        telemetryNodes.put("bms_status", ns.addVariableNode(machineFolder, name + ".bms_status", "IDLE"));
        telemetryNodes.put("weld_resistance", ns.addVariableNode(machineFolder, name + ".weld_resistance", 0.0));
        telemetryNodes.put("torque_result", ns.addVariableNode(machineFolder, name + ".torque_result", 0.0));
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
            System.err.printf("[ModulAndPackUnit02] Unsupported command '%s'%n", command);
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
                updateTelemetry(ns, "bms_status", "VERIFY");
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
                updateTelemetry(ns, "alarm_code", "STOP_MP");
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
        AlarmDefinition bmsFault = registerAlarm(
                "MAP02_BMS_FAULT",
                "BMS 통신 오류",
                AlarmSeverity.FAULT,
                AlarmCause.INTERNAL
        );
        AlarmDefinition overTorque = registerAlarm(
                "MAP02_OVERTORQUE",
                "체결 토크 과다",
                AlarmSeverity.WARNING,
                AlarmCause.INTERNAL
        );
        AlarmDefinition supplyWait = registerAlarm(
                "MAP02_SUPPLY_WAIT",
                "케이스 공급 대기",
                AlarmSeverity.NOTICE,
                AlarmCause.EXTERNAL
        );

        registerAlarmScenario(bmsFault, 0.00015, 6000, 13000);
        registerAlarmScenario(overTorque, 0.00019, 5000, 11000);
        registerAlarmScenario(supplyWait, 0.00012, 3000, 7000);
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

        boolean alignmentOk = cellAlignmentMm <= 0.13;
        boolean resistanceOk = moduleResistanceMOhm >= 3.35 && moduleResistanceMOhm <= 3.78;
        boolean weldOk = weldResistanceMOhm <= 0.86;
        boolean torqueOk = torqueNm >= 5.30 && torqueNm <= 5.90;

        boolean serialOk = true;
        int ngType = 0;
        if (!alignmentOk) {
            serialOk = false;
            ngType = NgType.CELL_ALIGNMENT;
        } else if (!resistanceOk) {
            serialOk = false;
            ngType = NgType.MODULE_RESISTANCE;
        } else if (!weldOk) {
            serialOk = false;
            ngType = NgType.WELD_RESISTANCE;
        } else if (!torqueOk || !bmsHealthy) {
            serialOk = false;
            ngType = NgType.TORQUE_OUT_OF_SPEC;
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

        updateTelemetry(ns, "bms_status", bmsHealthy ? "OK" : "WARN");
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
        cellAlignmentMm = Math.abs(rnd.nextGaussian()) * 0.055 + 0.035;
        moduleResistanceMOhm = 3.55 + (rnd.nextDouble() - 0.5) * 0.18;
        bmsHealthy = rnd.nextDouble() > 0.012;
        weldResistanceMOhm = 0.80 + (rnd.nextDouble() - 0.5) * 0.06;
        torqueNm = 5.55 + (rnd.nextDouble() - 0.5) * 0.22;
    }

    private void updateMetricTelemetry(MultiMachineNameSpace ns) {
        updateTelemetry(ns, "cell_alignment", cellAlignmentMm);
        updateTelemetry(ns, "module_resistance", moduleResistanceMOhm);
        updateTelemetry(ns, "bms_status", bmsHealthy ? "OK" : "WARN");
        updateTelemetry(ns, "weld_resistance", weldResistanceMOhm);
        updateTelemetry(ns, "torque_result", torqueNm);
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
        cellAlignmentMm = 0.0;
        moduleResistanceMOhm = 0.0;
        bmsHealthy = true;
        weldResistanceMOhm = 0.0;
        torqueNm = 0.0;
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
