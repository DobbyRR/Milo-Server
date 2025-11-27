package com.synclab.miloserver.machine.mainFactory.compositeLine.assemblyUnit3rd;

import com.synclab.miloserver.opcua.MultiMachineNameSpace;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;

public class AssemblyUnit02 extends com.synclab.miloserver.machine.mainFactory.cylindricalLine.assemblyUnit3rd.AssemblyUnit02 {

    public AssemblyUnit02(String name, String equipmentCode, UaFolderNode folder, MultiMachineNameSpace ns) {
        super(name, "CompositeLine", equipmentCode, folder, ns);
    }
}
