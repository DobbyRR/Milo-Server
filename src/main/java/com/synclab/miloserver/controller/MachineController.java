package com.synclab.miloserver.controller;

import com.synclab.miloserver.opcua.MultiMachineNameSpace;
import com.synclab.miloserver.opcua.ProductionLineController;
import com.synclab.miloserver.opcua.UaNodeManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/machine")
public class MachineController {
    private static final Logger log = LoggerFactory.getLogger(MachineController.class);
    private final UaNodeManager manager;
    private final MultiMachineNameSpace namespace;

    public MachineController(UaNodeManager manager, MultiMachineNameSpace namespace) {
        this.manager = manager;
        this.namespace = namespace;
    }

    @GetMapping("/{id}/{tag}")
    public Object read(@PathVariable String id, @PathVariable String tag) {
        return manager.readValue("Machine" + id + "." + tag);
    }

    @PostMapping("/{id}/{tag}")
    public void write(@PathVariable String id, @PathVariable String tag, @RequestParam Object value) {
        manager.writeValue("Machine" + id + "." + tag, value);
    }

    @PostMapping("/command")
    public ResponseEntity<Void> handleLineCommand(@RequestParam String factoryCode,
                                                  @RequestParam String lineCode,
                                                  @RequestBody MesCommandRequest request) {
        if (request == null || request.getAction() == null || request.getAction().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "action is required");
        }

        String normalizedFactoryCode = factoryCode.trim();
        String normalizedLineCode = lineCode.trim();

        log.info("MES ORDER PAYLOAD factoryCode={}, lineCode={}, action={}, orderNo={}, targetQty={}, itemCode={}",
                normalizedFactoryCode,
                normalizedLineCode,
                request.getAction(),
                request.getOrderNo(),
                request.getTargetQty(),
                request.getItemCode());

        ProductionLineController lineController = namespace.findLineController(normalizedFactoryCode, normalizedLineCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        String.format("Line not found for factoryCode=%s, lineCode=%s", normalizedFactoryCode, normalizedLineCode)));

        if ("START".equalsIgnoreCase(request.getAction())) {
            if (request.getOrderNo() == null || request.getOrderNo().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "orderNo is required for START");
            }
            if (request.getTargetQty() == null || request.getTargetQty() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "targetQty must be positive for START");
            }
        }

        lineController.processMesCommand(
                request.getAction(),
                request.getOrderNo(),
                request.getTargetQty(),
                request.getItemCode()
        );

        return ResponseEntity.accepted().build();
    }

    public static class MesCommandRequest {
        private String action;
        private String orderNo;
        private Integer targetQty;
        private String itemCode;

        public String getAction() {
            return action;
        }

        public void setAction(String action) {
            this.action = action;
        }

        public String getOrderNo() {
            return orderNo;
        }

        public void setOrderNo(String orderNo) {
            this.orderNo = orderNo;
        }

        public Integer getTargetQty() {
            return targetQty;
        }

        public void setTargetQty(Integer targetQty) {
            this.targetQty = targetQty;
        }

        public String getItemCode() {
            return itemCode;
        }

        public void setItemCode(String itemCode) {
            this.itemCode = itemCode;
        }
    }
}
