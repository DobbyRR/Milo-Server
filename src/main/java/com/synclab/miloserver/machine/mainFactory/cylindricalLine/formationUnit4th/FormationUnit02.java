package com.synclab.miloserver.machine.mainFactory.cylindricalLine.formationUnit4th;

import com.synclab.miloserver.opcua.MultiMachineNameSpace;
import com.synclab.miloserver.opcua.MultiMachineNameSpace;
import com.synclab.miloserver.opcua.UnitLogic;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;

import java.util.concurrent.ThreadLocalRandom;

public class FormationUnit02 extends UnitLogic {

    private static final double[] STAGE_DURATIONS_SEC = {4.0, 4.0, 4.0, 4.0};
    private static final double TOTAL_CYCLE_TIME_SEC = 16.0;
    private static final double TIME_ACCELERATION = 5.0;

    private int stageIndex = 0;
    private double stageElapsed = 0.0;
    private double cycleElapsed = 0.0;
    private double totalElapsedSeconds = 0.0;
    private int processedSerialCount = 0;
    private boolean currentSerialOkFlag = true;
    private int currentNgType = 0;

    private double chargeVoltage = 0.0;
    private double chargeCurrent = 0.0;
    private double cellTemperature = 0.0;
    private double internalResistance = 0.0;
    private double capacityAh = 0.0;

    /**
     * Formation NG Type codes (1~4)
     * 1 - 충전 전압 이상
     * 2 - 충전 전류 이상
     * 3 - 셀 온도 이상
     * 4 - 용량 부족
     */
    private static final String[] NG_TYPE_NAMES = {
            "충전 전압 이상",
            "충전 전류 이상",
            "셀 온도 이상",
            "용량 부족"
    };
    private static final class NgType {
        static final int CHARGE_VOLTAGE = 1;
        static final int CHARGE_CURRENT = 2;
        static final int CELL_TEMPERATURE = 3;
        static final int CAPACITY_DEFECT = 4;

        private NgType() {}
    }

    public FormationUnit02(String name, UaFolderNode folder, MultiMachineNameSpace ns) {
        super(name, folder);
        this.unitType = "FORMATION";
        this.lineId = "CylindricalLine";
        this.machineNo = 4;
        this.equipmentCode = "F1-CL1-FAU002";
        this.processId = "Formation";
        this.defaultPpm = 72;
        setUnitsPerCycle(1);
        configureEnergyProfile(1.4, 0.18, 14.5, 1.6);

        configureAlarms();
        setupCommonTelemetry(ns);
        registerNgTypeNames(NG_TYPE_NAMES);
        setupVariables(ns);
    }

    @Override
    public void setupVariables(MultiMachineNameSpace ns) {
        telemetryNodes.put("charge_voltage", ns.addVariableNode(machineFolder, name + ".charge_voltage", 0.0));
        telemetryNodes.put("charge_current", ns.addVariableNode(machineFolder, name + ".charge_current", 0.0));
        telemetryNodes.put("cell_temperature", ns.addVariableNode(machineFolder, name + ".cell_temperature", 25.0));
        telemetryNodes.put("capacity_ah", ns.addVariableNode(machineFolder, name + ".capacity_ah", 0.0));
        telemetryNodes.put("internal_resistance", ns.addVariableNode(machineFolder, name + ".internal_resistance", 0.0));
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
            System.err.printf("[FormationUnit02] Unsupported command '%s'%n", command);
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
                if (timeInState(3000)) {
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
                updateTelemetry(ns, "alarm_code", "STOP_FU");
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
        AlarmDefinition thermalRunaway = registerAlarm(
                "FAU02_THERMAL_RUNAWAY",
                "셀 열폭주 조짐",
                AlarmSeverity.EMERGENCY,
                AlarmCause.INTERNAL
        );
        AlarmDefinition chargerAcFail = registerAlarm(
                "FAU02_AC_FAIL",
                "충방전 전원 상실",
                AlarmSeverity.FAULT,
                AlarmCause.INTERNAL
        );
        AlarmDefinition upstreamHold = registerAlarm(
                "FAU02_UPSTREAM_HOLD",
                "전공정 이송 중단",
                AlarmSeverity.WARNING,
                AlarmCause.EXTERNAL
        );

        registerAlarmScenario(thermalRunaway, 0.00010, 9000, 20000);
        registerAlarmScenario(chargerAcFail, 0.00014, 7000, 15000);
        registerAlarmScenario(upstreamHold, 0.00017, 5000, 10000);
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

        boolean voltageOk = chargeVoltage >= 3.55 && chargeVoltage <= 3.65;
        boolean currentOk = chargeCurrent >= 1.45 && chargeCurrent <= 1.65;
        boolean temperatureOk = cellTemperature >= 26.8 && cellTemperature <= 32.2;
        boolean capacityOk = capacityAh >= 96.5;
        boolean resistanceOk = internalResistance >= 1.70 && internalResistance <= 1.96;

        boolean serialOk = true;
        int ngType = 0;
        if (!voltageOk) {
            serialOk = false;
            ngType = NgType.CHARGE_VOLTAGE;
        } else if (!currentOk) {
            serialOk = false;
            ngType = NgType.CHARGE_CURRENT;
        } else if (!temperatureOk) {
            serialOk = false;
            ngType = NgType.CELL_TEMPERATURE;
        } else if (!capacityOk || !resistanceOk) {
            serialOk = false;
            ngType = NgType.CAPACITY_DEFECT;
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
        chargeVoltage = 3.58 + (rnd.nextDouble() - 0.5) * 0.04;
        chargeCurrent = 1.55 + (rnd.nextDouble() - 0.5) * 0.10;
        cellTemperature = 28.5 + (rnd.nextDouble() - 0.5) * 2.0;
        internalResistance = 1.82 + (rnd.nextDouble() - 0.5) * 0.10;
        capacityAh = 98.0 + (rnd.nextDouble() - 0.5) * 2.5;
    }

    private void updateMetricTelemetry(MultiMachineNameSpace ns) {
        updateTelemetry(ns, "charge_voltage", chargeVoltage);
        updateTelemetry(ns, "charge_current", chargeCurrent);
        updateTelemetry(ns, "cell_temperature", cellTemperature);
        updateTelemetry(ns, "internal_resistance", internalResistance);
        updateTelemetry(ns, "capacity_ah", capacityAh);
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
        chargeVoltage = 0.0;
        chargeCurrent = 0.0;
        cellTemperature = 0.0;
        internalResistance = 0.0;
        capacityAh = 0.0;
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
