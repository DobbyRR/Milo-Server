package com.synclab.miloserver.machine.mainFactory.prismaticLine.finalInspection;

import com.synclab.miloserver.opcua.MultiMachineNameSpace;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;

public class FinalInspection01 extends com.synclab.miloserver.machine.mainFactory.cylindricalLine.finalInspection.FinalInspection01 {

    public FinalInspection01(String name, String equipmentCode, UaFolderNode folder, MultiMachineNameSpace ns) {
        super(name, "PrismaticLine", equipmentCode, folder, ns);
    }
}
