package rental.additional.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CarDto(
		UUID id,
		String vin,
		String model,
		String color,
		BigDecimal rentalCostPerDay,
		String city,
		String salonName) {
}
