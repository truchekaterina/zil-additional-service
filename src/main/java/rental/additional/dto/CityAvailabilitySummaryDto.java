package rental.additional.dto;

public record CityAvailabilitySummaryDto(
		String city,
		int totalCars,
		int availableCount,
		int unavailableCount) {
}
