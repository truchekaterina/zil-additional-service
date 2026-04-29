package rental.additional.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import rental.additional.client.MainCrudClient;
import rental.additional.dto.AdditionalStatsDto;
import rental.additional.dto.AvailabilityResponseDto;
import rental.additional.dto.CarDto;
import rental.additional.dto.CityAvailabilitySummaryDto;
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

		Set<UUID> rentedCarIds = rentedCarIdsForDate(date);

		List<CarDto> availableCars = carsInCity.stream()
				.filter(car -> !rentedCarIds.contains(car.id()))
				.toList();

		List<CarDto> unavailableCars = carsInCity.stream()
				.filter(car -> rentedCarIds.contains(car.id()))
				.toList();

		int total = carsInCity.size();
		return new AvailabilityResponseDto(
				normalizedCity,
				date,
				availableCars.size(),
				unavailableCars.size(),
				total,
				availableCars,
				unavailableCars,
				List.of());
	}

	/** Все города: на выбранную дату — сколько свободно / занято. */
	public AvailabilityResponseDto getAvailabilityAllCitiesForDate(LocalDate date) {
		Map<String, List<CarDto>> byCity = groupCarsByCity(mainCrudClient.getCars());
		Set<UUID> rentedCarIds = rentedCarIdsForDate(date);
		List<CityAvailabilitySummaryDto> rows = summarize(byCity, rentedCarIds);
		return withCityTotals(null, date, rows);
	}

	/** Все города: «на все даты» — машина доступна, если для неё нет ни одной аренды в данных. */
	public AvailabilityResponseDto getAvailabilityAllCitiesAllDates() {
		Map<String, List<CarDto>> byCity = groupCarsByCity(mainCrudClient.getCars());
		Set<UUID> carsWithAnyRent = carsWithAnyRent();
		List<CityAvailabilitySummaryDto> rows = summarizeNeverRented(byCity, carsWithAnyRent);
		return withCityTotals(null, null, rows);
	}

	/** Один город без даты: та же логика, что и для «все даты». */
	public AvailabilityResponseDto getAvailabilityForCityAllDates(String city) {
		String key = city.trim();
		return getAvailabilityAllCitiesAllDates().cities().stream()
				.filter(row -> row.city().equalsIgnoreCase(key))
				.findFirst()
				.map(row -> new AvailabilityResponseDto(
						row.city(),
						null,
						row.availableCount(),
						row.unavailableCount(),
						row.totalCars(),
						List.of(),
						List.of(),
						List.of()))
				.orElse(new AvailabilityResponseDto(
						key,
						null,
						0,
						0,
						0,
						List.of(),
						List.of(),
						List.of()));
	}

	public AdditionalStatsDto getStats() {
		return new AdditionalStatsDto(
				mainCrudClient.getCars().size(),
				mainCrudClient.getClients().size(),
				mainCrudClient.getRents().size(),
				STATS_SOURCE);
	}

	private Map<String, List<CarDto>> groupCarsByCity(List<CarDto> cars) {
		return cars.stream()
				.filter(car -> car.city() != null && !car.city().isBlank())
				.collect(Collectors.groupingBy(car -> car.city()));
	}

	private Set<UUID> rentedCarIdsForDate(LocalDate date) {
		return mainCrudClient.getRents().stream()
				.filter(rent -> rent.carId() != null)
				.filter(rent -> includesDate(rent, date))
				.map(RentDto::carId)
				.collect(Collectors.toSet());
	}

	private Set<UUID> carsWithAnyRent() {
		return mainCrudClient.getRents().stream()
				.map(RentDto::carId)
				.filter(Objects::nonNull)
				.collect(Collectors.toSet());
	}

	private List<CityAvailabilitySummaryDto> summarize(
			Map<String, List<CarDto>> byCity,
			Set<UUID> rentedCarIdsForDate) {
		List<CityAvailabilitySummaryDto> rows = new ArrayList<>();
		for (Map.Entry<String, List<CarDto>> e : byCity.entrySet()) {
			List<CarDto> inCity = e.getValue();
			int total = inCity.size();
			long unavailable = inCity.stream().filter(car -> rentedCarIdsForDate.contains(car.id())).count();
			int u = Math.toIntExact(unavailable);
			rows.add(new CityAvailabilitySummaryDto(e.getKey(), total, total - u, u));
		}
		rows.sort(Comparator.comparing(CityAvailabilitySummaryDto::city, String.CASE_INSENSITIVE_ORDER));
		return rows;
	}

	private List<CityAvailabilitySummaryDto> summarizeNeverRented(
			Map<String, List<CarDto>> byCity,
			Set<UUID> carsWithAnyRent) {
		List<CityAvailabilitySummaryDto> rows = new ArrayList<>();
		for (Map.Entry<String, List<CarDto>> e : byCity.entrySet()) {
			List<CarDto> inCity = e.getValue();
			int total = inCity.size();
			long rented = inCity.stream().filter(car -> carsWithAnyRent.contains(car.id())).count();
			int u = Math.toIntExact(rented);
			rows.add(new CityAvailabilitySummaryDto(e.getKey(), total, total - u, u));
		}
		rows.sort(Comparator.comparing(CityAvailabilitySummaryDto::city, String.CASE_INSENSITIVE_ORDER));
		return rows;
	}

	private boolean includesDate(RentDto rent, LocalDate date) {
		return rent.startDate() != null
				&& rent.endDate() != null
				&& !date.isBefore(rent.startDate())
				&& !date.isAfter(rent.endDate());
	}

	private AvailabilityResponseDto withCityTotals(
			String city,
			LocalDate date,
			List<CityAvailabilitySummaryDto> rows) {
		int sumAvailable = rows.stream().mapToInt(CityAvailabilitySummaryDto::availableCount).sum();
		int sumUnavailable = rows.stream().mapToInt(CityAvailabilitySummaryDto::unavailableCount).sum();
		int sumTotal = rows.stream().mapToInt(CityAvailabilitySummaryDto::totalCars).sum();
		return new AvailabilityResponseDto(
				city, date, sumAvailable, sumUnavailable, sumTotal, List.of(), List.of(), rows);
	}
}
