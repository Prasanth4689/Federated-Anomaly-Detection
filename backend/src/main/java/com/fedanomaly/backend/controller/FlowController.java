package com.fedanomaly.backend.controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import com.fedanomaly.backend.repository.NetworkFlowRepository;
import com.fedanomaly.backend.model.NetworkFlow;
import java.util.List;

@RestController
@RequestMapping("/api/flows")
public class FlowController {
    
    private final NetworkFlowRepository flowRepository;
    
    public FlowController(NetworkFlowRepository flowRepository) {
        this.flowRepository = flowRepository;
    }

    @GetMapping
    public List<NetworkFlow> getFlows() { 
        return flowRepository.findAll(); 
    }
    
    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<String> exportFlows() {
        List<NetworkFlow> flows = flowRepository.findAll();
        StringBuilder csv = new StringBuilder();
        csv.append("FlowID,Timestamp,SourceNode,DestNode,Protocol,DurationMs,PacketCount,TotalBytes,AvgPacketSize,PacketsPerSec,BytesPerSec,Label,AnomalyScore\n");
        for (NetworkFlow f : flows) {
            csv.append(String.format("%s,%s,%s,%s,%s,%d,%d,%d,%.2f,%.2f,%.2f,%s,%.4f\n",
                f.getId(), f.getTimestamp(), f.getSourceNode(), f.getDestNode(), f.getProtocol(),
                f.getDurationMs(), f.getPacketCount(), f.getTotalBytes(), f.getAvgPacketSize(),
                f.getPacketsPerSec(), f.getBytesPerSec(), f.getLabel(), f.getAnomalyScore()
            ));
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"network_flows.csv\"")
                .body(csv.toString());
    }
}
