package rental.additional.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RentDto(
		UUID id,
		UUID carId,
		UUID clientId,
		LocalDate startDate,
		LocalDate endDate,
		BigDecimal totalCost) {
}
