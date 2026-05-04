package rental.additional.controller;

import java.time.LocalDate;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import rental.additional.dto.AdditionalStatsDto;
import rental.additional.dto.AvailabilityNewResponseDto;
import rental.additional.dto.AvailabilityResponseDto;
import rental.additional.observability.ObservabilityService;
import rental.additional.service.AdditionalRentalService;

@RestController
@RequestMapping("/additional")
public class AdditionalController {

	private final AdditionalRentalService additionalRentalService;
	private final ObservabilityService observabilityService;

	public AdditionalController(
			AdditionalRentalService additionalRentalService,
			ObservabilityService observabilityService) {
		this.additionalRentalService = additionalRentalService;
		this.observabilityService = observabilityService;
	}

	@GetMapping("/health")
	public Map<String, String> health() {
		return observabilityService.timed("web.additional.health", () -> Map.of(
				"status", "UP",
				"service", "zil-additional-service"));
	}

	@GetMapping({ "/cars/availability", "/cars/available" })
	public AvailabilityResponseDto getAvailability(
			@RequestParam(required = false) String city,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
		return observabilityService.timed("web.additional.availability", () -> resolveAvailability(city, date));
	}

	/**
	 * Как {@link #getAvailability}, но без полей city, date и без списков availableCars / unavailableCars.
	 */
	@GetMapping("/cars/availability_new")
	public AvailabilityNewResponseDto getAvailabilityNew(
			@RequestParam(required = false) String city,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
		return observabilityService.timed("web.additional.availability_new", () -> {
			AvailabilityResponseDto full = resolveAvailability(city, date);
			return new AvailabilityNewResponseDto(
					full.availableCount(),
					full.unavailableCount(),
					full.totalCars(),
					full.cities());
		});
	}

	@GetMapping("/stats")
	public AdditionalStatsDto getStats() {
		return observabilityService.timed("web.additional.stats", additionalRentalService::getStats);
	}

	private AvailabilityResponseDto resolveAvailability(String city, LocalDate date) {
		String normalizedCity = city == null ? null : city.trim();
		if (normalizedCity != null && normalizedCity.isEmpty()) {
			normalizedCity = null;
		}

		if (normalizedCity != null && date != null) {
			return additionalRentalService.getAvailability(normalizedCity, date);
		}
		if (normalizedCity != null) {
			return additionalRentalService.getAvailabilityForCityAllDates(normalizedCity);
		}
		if (date != null) {
			return additionalRentalService.getAvailabilityAllCitiesForDate(date);
		}
		return additionalRentalService.getAvailabilityAllCitiesAllDates();
	}
}
