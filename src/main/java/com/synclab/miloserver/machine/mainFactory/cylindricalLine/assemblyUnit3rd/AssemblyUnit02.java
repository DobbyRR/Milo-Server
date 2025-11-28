package com.synclab.miloserver.machine.mainFactory.cylindricalLine.assemblyUnit3rd;

import com.synclab.miloserver.opcua.MultiMachineNameSpace;
import com.synclab.miloserver.opcua.UnitLogic;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;

import java.util.concurrent.ThreadLocalRandom;

public class AssemblyUnit02 extends UnitLogic {

    private static final double[] STAGE_DURATIONS_SEC = {3.0, 4.0, 3.0, 2.0, 2.0};
    private static final double TOTAL_CYCLE_TIME_SEC = 14.0;
    private static final double TIME_ACCELERATION = 5.0;

    private int stageIndex = 0;
    private double stageElapsed = 0.0;
    private double cycleElapsed = 0.0;
    private double totalElapsedSeconds = 0.0;
    private int processedSerialCount = 0;
    private boolean currentSerialOkFlag = true;
    private int currentNgType = 0;

    private double notchDimDevUm = 0.0;
    private double stackAlignDevUm = 0.0;
    private double windingTensionN = 0.0;
    private double weldResistanceMOhm = 0.0;
    private double leakRatePaS = 0.0;
    private double fillVolumeMl = 0.0;

    /**
     * Assembly NG Type codes (1~4)
     * 1 - 적층 정렬 불량
     * 2 - 권선 장력 이상
     * 3 - 용접 품질 불량
     * 4 - 누액/밀봉 불량
     */
    private static final String[] NG_TYPE_NAMES = {
            "적층 정렬 불량",
            "권선 장력 이상",
            "용접 품질 불량",
            "누액/밀봉 불량"
    };
    private static final class NgType {
        static final int STACK_ALIGNMENT = 1;
        static final int WINDING_TENSION = 2;
        static final int WELD_QUALITY = 3;
        static final int LEAK_TEST = 4;

        private NgType() {}
    }

    public AssemblyUnit02(String name,
                          String lineId,
                          String equipmentPrefix,
                          UaFolderNode folder,
                          MultiMachineNameSpace ns) {
        super(name, folder);
        this.unitType = "ASSEMBLY";
        this.lineId = lineId;
        this.machineNo = 3;
        this.equipmentCode = equipmentPrefix;
        this.processId = "Assembly";
        this.defaultPpm = 82;
        setUnitsPerCycle(1);
        configureEnergyProfile(0.72, 0.08, 6.8, 0.85);

        configureAlarms();
        setupCommonTelemetry(ns);
        registerNgTypeNames(NG_TYPE_NAMES);
        setupVariables(ns);
    }

    @Override
    public void setupVariables(MultiMachineNameSpace ns) {
        telemetryNodes.put("stack_alignment", ns.addVariableNode(machineFolder, name + ".stack_alignment", 0.0));
        telemetryNodes.put("winding_tension", ns.addVariableNode(machineFolder, name + ".winding_tension", 0.0));
        telemetryNodes.put("weld_quality", ns.addVariableNode(machineFolder, name + ".weld_quality", 0.0));
        telemetryNodes.put("leak_test_result", ns.addVariableNode(machineFolder, name + ".leak_test_result", "IDLE"));
        telemetryNodes.put("electrolyte_fill", ns.addVariableNode(machineFolder, name + ".electrolyte_fill", 0.0));

        telemetryNodes.put("notch_dim_dev_um", ns.addVariableNode(machineFolder, name + ".notch_dim_dev_um", 0.0));
        telemetryNodes.put("stack_align_dev_um", ns.addVariableNode(machineFolder, name + ".stack_align_dev_um", 0.0));
        telemetryNodes.put("winding_tension_N", ns.addVariableNode(machineFolder, name + ".winding_tension_N", 0.0));
        telemetryNodes.put("weld_resistance_mOhm", ns.addVariableNode(machineFolder, name + ".weld_resistance_mOhm", 0.0));
        telemetryNodes.put("leak_rate_Pa_s", ns.addVariableNode(machineFolder, name + ".leak_rate_Pa_s", 0.0));
        telemetryNodes.put("fill_volume_ml", ns.addVariableNode(machineFolder, name + ".fill_volume_ml", 0.0));
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
            System.err.printf("[AssemblyUnit02] Unsupported command '%s'%n", command);
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
                updateTelemetry(ns, "leak_test_result", "VERIFY");
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
                updateTelemetry(ns, "alarm_code", "STOP_AU");
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
        AlarmDefinition stackShift = registerAlarm(
                "AU02_STACK_SHIFT",
                "적층 위치 편차",
                AlarmSeverity.WARNING,
                AlarmCause.INTERNAL
        );
        AlarmDefinition welderInterlock = registerAlarm(
                "AU02_WELDER_INTERLOCK",
                "탭 용접 인터락",
                AlarmSeverity.FAULT,
                AlarmCause.INTERNAL
        );
        AlarmDefinition gasLeak = registerAlarm(
                "AU02_GAS_LEAK",
                "충전 셀 가스 감지",
                AlarmSeverity.EMERGENCY,
                AlarmCause.INTERNAL
        );
        AlarmDefinition feederDelay = registerAlarm(
                "AU02_FEEDER_DELAY",
                "외부 모듈 공급 지연",
                AlarmSeverity.NOTICE,
                AlarmCause.EXTERNAL
        );

        registerAlarmScenario(stackShift, 0.00018, 5000, 11000);
        registerAlarmScenario(welderInterlock, 0.00013, 6000, 12000);
        registerAlarmScenario(gasLeak, 0.00009, 9000, 18000);
        registerAlarmScenario(feederDelay, 0.00011, 3000, 7000);
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

        boolean notchOk = Math.abs(notchDimDevUm) <= 12.0;
        boolean stackOk = Math.abs(stackAlignDevUm) <= 18.0;
        boolean tensionOk = windingTensionN >= 36.0 && windingTensionN <= 44.0;
        boolean weldOk = weldResistanceMOhm <= 1.6;
        boolean leakOk = leakRatePaS <= 1.05;
        boolean fillOk = fillVolumeMl >= 4.75 && fillVolumeMl <= 5.15;

        boolean serialOk = true;
        int ngType = 0;
        if (!stackOk) {
            serialOk = false;
            ngType = NgType.STACK_ALIGNMENT;
        } else if (!tensionOk) {
            serialOk = false;
            ngType = NgType.WINDING_TENSION;
        } else if (!weldOk) {
            serialOk = false;
            ngType = NgType.WELD_QUALITY;
        } else if (!leakOk || !fillOk) {
            serialOk = false;
            ngType = NgType.LEAK_TEST;
        }

        processedSerialCount++;
        updateProducedQuantity(ns, producedQuantity + 1);

        if (serialOk) {
            completeActiveSerialOk(ns);
            updateQualityCounts(ns, okCount + 1, ngCount);
            currentSerialOkFlag = true;
            currentNgType = 0;
            updateTelemetry(ns, "leak_test_result", "PASS");
        } else {
            completeActiveSerialNg(ns, ngType);
            updateQualityCounts(ns, okCount, ngCount + 1);
            currentSerialOkFlag = false;
            currentNgType = ngType;
            updateTelemetry(ns, "leak_test_result", "FAIL");
        }

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
        notchDimDevUm = (rnd.nextDouble() - 0.5) * 8.0;
        stackAlignDevUm = (rnd.nextDouble() - 0.5) * 14.0;
        windingTensionN = 40.5 + (rnd.nextDouble() - 0.5) * 5.0;
        weldResistanceMOhm = 1.1 + (rnd.nextDouble() - 0.5) * 0.5;
        leakRatePaS = Math.max(0.0, 0.6 + (rnd.nextDouble() - 0.5) * 0.5);
        fillVolumeMl = 5.0 + (rnd.nextDouble() - 0.5) * 0.25;
    }

    private void updateMetricTelemetry(MultiMachineNameSpace ns) {
        updateTelemetry(ns, "notch_dim_dev_um", notchDimDevUm);
        updateTelemetry(ns, "stack_align_dev_um", stackAlignDevUm);
        updateTelemetry(ns, "winding_tension_N", windingTensionN);
        updateTelemetry(ns, "weld_resistance_mOhm", weldResistanceMOhm);
        updateTelemetry(ns, "leak_rate_Pa_s", leakRatePaS);
        updateTelemetry(ns, "fill_volume_ml", fillVolumeMl);
        updateTelemetry(ns, "stack_alignment", Math.abs(stackAlignDevUm));
        updateTelemetry(ns, "winding_tension", windingTensionN);
        updateTelemetry(ns, "weld_quality", 100.0 - weldResistanceMOhm);
        updateTelemetry(ns, "electrolyte_fill", fillVolumeMl);
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
        updateTelemetry(ns, "current_serial", "");
        updateTelemetry(ns, "serial_ok", true);
        updateTelemetry(ns, "ng_type", 0);
        updateTelemetry(ns, "processed_count", 0);
        updateTelemetry(ns, "good_count", 0);
        updateTelemetry(ns, "ng_count", 0);
        updateTelemetry(ns, "t_in_cycle_sec", 0.0);
        updateTelemetry(ns, "notch_dim_dev_um", 0.0);
        updateTelemetry(ns, "stack_align_dev_um", 0.0);
        updateTelemetry(ns, "winding_tension_N", 0.0);
        updateTelemetry(ns, "weld_resistance_mOhm", 0.0);
        updateTelemetry(ns, "leak_rate_Pa_s", 0.0);
        updateTelemetry(ns, "fill_volume_ml", 0.0);
    }
}
