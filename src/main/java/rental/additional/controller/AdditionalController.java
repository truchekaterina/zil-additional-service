package rental.additional.controller;

import java.time.LocalDate;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import rental.additional.dto.AdditionalStatsDto;
import rental.additional.dto.AvailabilityResponseDto;
import rental.additional.service.AdditionalRentalService;

@Validated
@RestController
@RequestMapping("/additional")
public class AdditionalController {

	private final AdditionalRentalService additionalRentalService;

	public AdditionalController(AdditionalRentalService additionalRentalService) {
		this.additionalRentalService = additionalRentalService;
	}

	@GetMapping("/health")
	public Map<String, String> health() {
		return Map.of("status", "UP", "service", "zil-additional-service");
	}

	@GetMapping({ "/cars/availability", "/cars/available" })
	public AvailabilityResponseDto getAvailability(
			@RequestParam @NotBlank String city,
			@RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
		return additionalRentalService.getAvailability(city, date);
	}

	@GetMapping("/stats")
	public AdditionalStatsDto getStats() {
		return additionalRentalService.getStats();
	}
}
