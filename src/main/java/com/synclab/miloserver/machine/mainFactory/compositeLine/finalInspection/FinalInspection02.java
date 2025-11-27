package com.synclab.miloserver.machine.mainFactory.compositeLine.finalInspection;

import com.synclab.miloserver.opcua.MultiMachineNameSpace;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;

public class FinalInspection02 extends com.synclab.miloserver.machine.mainFactory.cylindricalLine.finalInspection.FinalInspection02 {

    public FinalInspection02(String name, String equipmentCode, UaFolderNode folder, MultiMachineNameSpace ns) {
        super(name, "CompositeLine", equipmentCode, folder, ns);
    }
}
