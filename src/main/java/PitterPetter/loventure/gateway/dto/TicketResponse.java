package PitterPetter.loventure.gateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TicketResponse {
    private String status;
    
    @JsonProperty("coupleId")
    private String coupleId;
    
    @JsonProperty("ticket")
    private Integer ticket;
    
    
    @JsonProperty("lastSyncedAt")
    private String lastSyncedAt;
}
