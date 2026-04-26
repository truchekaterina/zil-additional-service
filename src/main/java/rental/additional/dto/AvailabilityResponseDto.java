package rental.additional.dto;

import java.time.LocalDate;
import java.util.List;

public record AvailabilityResponseDto(
		String city,
		LocalDate date,
		List<CarDto> availableCars,
		List<CarDto> unavailableCars) {
}
