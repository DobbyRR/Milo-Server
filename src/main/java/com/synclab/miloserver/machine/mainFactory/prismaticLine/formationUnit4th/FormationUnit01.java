package com.synclab.miloserver.machine.mainFactory.prismaticLine.formationUnit4th;

import com.synclab.miloserver.opcua.MultiMachineNameSpace;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;

public class FormationUnit01 extends com.synclab.miloserver.machine.mainFactory.cylindricalLine.formationUnit4th.FormationUnit01 {

    public FormationUnit01(String name, String equipmentCode, UaFolderNode folder, MultiMachineNameSpace ns) {
        super(name, "PrismaticLine", equipmentCode, folder, ns);
    }
}
