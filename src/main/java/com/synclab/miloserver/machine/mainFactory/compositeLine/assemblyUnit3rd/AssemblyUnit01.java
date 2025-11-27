package com.synclab.miloserver.machine.mainFactory.compositeLine.assemblyUnit3rd;

import com.synclab.miloserver.opcua.MultiMachineNameSpace;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;

public class AssemblyUnit01 extends com.synclab.miloserver.machine.mainFactory.cylindricalLine.assemblyUnit3rd.AssemblyUnit01 {

    public AssemblyUnit01(String name, String equipmentCode, UaFolderNode folder, MultiMachineNameSpace ns) {
        super(name, "CompositeLine", equipmentCode, folder, ns);
    }
}
