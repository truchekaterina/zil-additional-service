package rental.additional.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import rental.additional.client.MainCrudClient;
import rental.additional.dto.AdditionalStatsDto;
import rental.additional.dto.AvailabilityResponseDto;
import rental.additional.dto.CarDto;
import rental.additional.dto.RentDto;
import rental.additional.observability.ObservabilityService;

@Service
public class AdditionalRentalService {

	private static final String STATS_SOURCE = "main-crud-service";

	private final MainCrudClient mainCrudClient;
	private final ObservabilityService observabilityService;

	public AdditionalRentalService(MainCrudClient mainCrudClient, ObservabilityService observabilityService) {
		this.mainCrudClient = mainCrudClient;
		this.observabilityService = observabilityService;
	}

	public AvailabilityResponseDto getAvailability(String city, LocalDate date) {
		List<CarDto> allCars = mainCrudClient.getCars();
		List<RentDto> allRents = mainCrudClient.getRents();
		long t0 = System.nanoTime();
		try {
			String normalizedCity = city.trim();
			String cityKey = normalizedCity.toLowerCase(Locale.ROOT);

			List<CarDto> carsInCity = allCars.stream()
					.filter(car -> car.city() != null)
					.filter(car -> car.city().toLowerCase(Locale.ROOT).equals(cityKey))
					.toList();

			Set<UUID> rentedCarIds = allRents.stream()
					.filter(rent -> rent.carId() != null)
					.filter(rent -> includesDate(rent, date))
					.map(RentDto::carId)
					.collect(Collectors.toSet());

			List<CarDto> availableCars = carsInCity.stream()
					.filter(car -> !rentedCarIds.contains(car.id()))
					.toList();

			List<CarDto> unavailableCars = carsInCity.stream()
					.filter(car -> rentedCarIds.contains(car.id()))
					.toList();

			return new AvailabilityResponseDto(normalizedCity, date, availableCars, unavailableCars);
		} finally {
			observabilityService.record("service.additional.availability_compute", System.nanoTime() - t0);
		}
	}

	public AdditionalStatsDto getStats() {
		int cars = mainCrudClient.getCars().size();
		int clients = mainCrudClient.getClients().size();
		int rents = mainCrudClient.getRents().size();
		long t0 = System.nanoTime();
		try {
			return new AdditionalStatsDto(cars, clients, rents, STATS_SOURCE);
		} finally {
			observabilityService.record("service.additional.stats_build", System.nanoTime() - t0);
		}
	}

	private boolean includesDate(RentDto rent, LocalDate date) {
		return rent.startDate() != null
				&& rent.endDate() != null
				&& !date.isBefore(rent.startDate())
				&& !date.isAfter(rent.endDate());
	}
}
