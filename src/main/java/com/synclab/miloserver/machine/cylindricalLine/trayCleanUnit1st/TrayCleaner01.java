package com.synclab.miloserver.machine.cylindricalLine.trayCleanUnit1st;

import com.synclab.miloserver.opcua.MultiMachineNameSpace;
import com.synclab.miloserver.opcua.UnitLogic;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class TrayCleaner01 extends UnitLogic {

    private long stateStartTime = System.currentTimeMillis();
    private int cycleCount = 0;

    // 트레이 클리너 개별 항목
    private final Map<String, Object> localTelemetry = new HashMap<>();

    public TrayCleaner01(String name, UaFolderNode folder, MultiMachineNameSpace ns) {
        super(name, folder);
        this.unitType = "TRAY_CLEAN";
        this.lineId = "CylindricalLine";
        this.machineNo = 1;
        this.equipmentId = "TC-01";
        this.processId = "DryClean";

        setupCommonTelemetry(ns);
        setupVariables(ns);
    }

    @Override
    public void setupVariables(MultiMachineNameSpace ns) {
        telemetryNodes.put("conveyor_id", ns.addVariableNode(folder, name + ".conveyor_id", "CV_01_BRANCH_A"));
        telemetryNodes.put("source_unit", ns.addVariableNode(folder, name + ".source_unit", "LOAD_UNIT"));
        telemetryNodes.put("target_unit", ns.addVariableNode(folder, name + ".target_unit", "ELECTRODE_UNIT"));
        telemetryNodes.put("direction", ns.addVariableNode(folder, name + ".direction", "FWD"));
        telemetryNodes.put("branch_state", ns.addVariableNode(folder, name + ".branch_state", "CENTER"));
        telemetryNodes.put("occupied", ns.addVariableNode(folder, name + ".occupied", false));
        telemetryNodes.put("tray_id", ns.addVariableNode(folder, name + ".tray_id", ""));
        telemetryNodes.put("speed", ns.addVariableNode(folder, name + ".speed", 0.0));
        telemetryNodes.put("jam_alarm", ns.addVariableNode(folder, name + ".jam_alarm", false));
        telemetryNodes.put("sensor_status", ns.addVariableNode(folder, name + ".sensor_status", "{}"));
        telemetryNodes.put("transfer_time", ns.addVariableNode(folder, name + ".transfer_time", 0.0));

        telemetryNodes.put("surface_cleanliness", ns.addVariableNode(folder, name + ".surface_cleanliness", 0.0));
        telemetryNodes.put("static_level", ns.addVariableNode(folder, name + ".static_level", 0.0));
        telemetryNodes.put("air_pressure", ns.addVariableNode(folder, name + ".air_pressure", 0.0));
        telemetryNodes.put("tray_tag_valid", ns.addVariableNode(folder, name + ".tray_tag_valid", false));
    }

    @Override
    public void onCommand(MultiMachineNameSpace ns, String command) {
        switch (command.toUpperCase()) {
            case "START":
                if (state.equals("IDLE")) changeState(ns, "STARTING");
                break;
            case "RESET":
                changeState(ns, "RESETTING");
                break;
            case "STOP":
                changeState(ns, "STOPPING");
                break;
        }
    }

    @Override
    public void simulateStep(MultiMachineNameSpace ns) {
        switch (state) {
            case "STARTING":
                if (timeInState(2000)) changeState(ns, "EXECUTE");
                break;

            case "EXECUTE":
                updateTelemetry(ns,"surface_cleanliness", Math.min(100, Math.random() * 100));
                updateTelemetry(ns,"static_level", Math.random() * 0.1);
                updateTelemetry(ns,"air_pressure", 5 + Math.random() * 2);
                updateTelemetry(ns,"tray_tag_valid", true);
                updateTelemetry(ns,"occupied", true);
                updateTelemetry(ns,"speed", 0.25 + Math.random() * 0.1);
                updateTelemetry(ns,"transfer_time", 5.0 + Math.random());
                updateTelemetry(ns,"cycle_time", cycleTime = 7.0);
                updateTelemetry(ns,"uptime", uptime += 1.0);
                updateTelemetry(ns,"energy_consumption", energyConsumption += 0.05);
                updateTelemetry(ns,"PPM", ppm = 60);
                updateTelemetry(ns,"OEE", oee = 95.5);
                if (timeInState(5000)) changeState(ns, "COMPLETING");
                break;

            case "COMPLETING":
                cycleCount++;
                updateTelemetry(ns,"occupied", false);
                updateTelemetry(ns,"tray_tag_valid", false);
                if (timeInState(2000)) changeState(ns, "COMPLETE");
                break;

            case "COMPLETE":
                if (timeInState(1000)) changeState(ns, "RESETTING");
                break;

            case "RESETTING":
                if (timeInState(2000)) changeState(ns, "IDLE");
                break;

            case "STOPPING":
                updateTelemetry(ns,"alarm_code", "STOP_01");
                updateTelemetry(ns,"alarm_level", "INFO");
                if (timeInState(1000)) changeState(ns, "IDLE");
                break;
        }
    }

    private void changeState(MultiMachineNameSpace ns, String newState) {
        this.state = newState;
        this.stateStartTime = System.currentTimeMillis();
        updateTelemetry(ns,"state", newState);
        System.out.printf("[%s] → %s%n", name, newState);
    }

    private boolean timeInState(long ms) {
        return System.currentTimeMillis() - stateStartTime > ms;
    }
}
