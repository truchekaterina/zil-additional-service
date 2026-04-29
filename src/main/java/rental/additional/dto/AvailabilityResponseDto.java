package rental.additional.dto;

import java.time.LocalDate;
import java.util.List;

public record AvailabilityResponseDto(
		String city,
		LocalDate date,
		int availableCount,
		int unavailableCount,
		int totalCars,
		List<CarDto> availableCars,
		List<CarDto> unavailableCars,
		List<CityAvailabilitySummaryDto> cities) {
}
