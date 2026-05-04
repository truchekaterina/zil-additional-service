package rental.additional.dto;

import java.util.List;

public record AvailabilityNewResponseDto(
		int availableCount,
		int unavailableCount,
		int totalCars,
		List<CityAvailabilitySummaryDto> cities) {
}
