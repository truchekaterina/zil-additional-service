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

@Service
public class AdditionalRentalService {

	private static final String STATS_SOURCE = "main-crud-service";

	private final MainCrudClient mainCrudClient;

	public AdditionalRentalService(MainCrudClient mainCrudClient) {
		this.mainCrudClient = mainCrudClient;
	}

	public AvailabilityResponseDto getAvailability(String city, LocalDate date) {
		String normalizedCity = city.trim();
		String cityKey = normalizedCity.toLowerCase(Locale.ROOT);

		List<CarDto> carsInCity = mainCrudClient.getCars().stream()
				.filter(car -> car.city() != null)
				.filter(car -> car.city().toLowerCase(Locale.ROOT).equals(cityKey))
				.toList();

		Set<UUID> rentedCarIds = mainCrudClient.getRents().stream()
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
	}

	public AdditionalStatsDto getStats() {
		return new AdditionalStatsDto(
				mainCrudClient.getCars().size(),
				mainCrudClient.getClients().size(),
				mainCrudClient.getRents().size(),
				STATS_SOURCE);
	}

	private boolean includesDate(RentDto rent, LocalDate date) {
		return rent.startDate() != null
				&& rent.endDate() != null
				&& !date.isBefore(rent.startDate())
				&& !date.isAfter(rent.endDate());
	}
}
