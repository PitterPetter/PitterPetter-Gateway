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
    private Long coupleId;
    
    @JsonProperty("tickat")
    private Integer tickat;
    
    @JsonProperty("reroll")
    private Integer reroll;
    
    @JsonProperty("loveDay")
    private Long loveDay;
    
    @JsonProperty("diaryCount")
    private Integer diaryCount;
    
    @JsonProperty("lastSyncedAt")
    private String lastSyncedAt;
}
