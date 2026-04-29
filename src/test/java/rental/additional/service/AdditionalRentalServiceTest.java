package rental.additional.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import rental.additional.client.MainCrudClient;
import rental.additional.dto.AdditionalStatsDto;
import rental.additional.dto.AvailabilityResponseDto;
import rental.additional.dto.CarDto;
import rental.additional.dto.ClientDto;
import rental.additional.dto.RentDto;

class AdditionalRentalServiceTest {

	private static final UUID AVAILABLE_MOSCOW_CAR_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
	private static final UUID RENTED_MOSCOW_CAR_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");
	private static final UUID OTHER_CITY_CAR_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440003");

	private final FakeMainCrudClient mainCrudClient = new FakeMainCrudClient();
	private final AdditionalRentalService service = new AdditionalRentalService(mainCrudClient);

	@Test
	void getAvailabilitySplitsCityCarsByRentDate() {
		AvailabilityResponseDto response = service.getAvailability("moscow", LocalDate.parse("2026-04-03"));

		assertEquals("moscow", response.city());
		assertEquals(LocalDate.parse("2026-04-03"), response.date());
		assertEquals(1, response.availableCount());
		assertEquals(1, response.unavailableCount());
		assertEquals(2, response.totalCars());
		assertEquals(1, response.availableCars().size());
		assertEquals(1, response.unavailableCars().size());
		assertEquals(AVAILABLE_MOSCOW_CAR_ID, response.availableCars().getFirst().id());
		assertEquals(RENTED_MOSCOW_CAR_ID, response.unavailableCars().getFirst().id());
		assertTrue(response.availableCars().stream().noneMatch(car -> OTHER_CITY_CAR_ID.equals(car.id())));
		assertTrue(response.cities().isEmpty());
	}

	@Test
	void getAvailabilityAllCitiesAllDatesUsesNeverRentedSemantics() {
		AvailabilityResponseDto response = service.getAvailabilityAllCitiesAllDates();

		assertEquals(1, response.availableCount());
		assertEquals(2, response.unavailableCount());
		assertEquals(3, response.totalCars());
		assertEquals(2, response.cities().size());
	}

	@Test
	void getStatsCountsMainServiceCollections() {
		AdditionalStatsDto stats = service.getStats();

		assertEquals(3, stats.cars());
		assertEquals(1, stats.clients());
		assertEquals(2, stats.rents());
		assertEquals("main-crud-service", stats.source());
	}

	private static final class FakeMainCrudClient implements MainCrudClient {

		@Override
		public List<CarDto> getCars() {
			return List.of(
					car(AVAILABLE_MOSCOW_CAR_ID, "Toyota Camry", "Moscow"),
					car(RENTED_MOSCOW_CAR_ID, "Honda Accord", "Moscow"),
					car(OTHER_CITY_CAR_ID, "Kia Rio", "Saint Petersburg"));
		}

		@Override
		public List<ClientDto> getClients() {
			return List.of(new ClientDto(
					UUID.fromString("660e8400-e29b-41d4-a716-446655440001"),
					"Ivan Petrov",
					"77 77 123456",
					"+79000000000"));
		}

		@Override
		public List<RentDto> getRents() {
			return List.of(
					new RentDto(
							UUID.fromString("770e8400-e29b-41d4-a716-446655440001"),
							RENTED_MOSCOW_CAR_ID,
							UUID.fromString("660e8400-e29b-41d4-a716-446655440001"),
							LocalDate.parse("2026-04-01"),
							LocalDate.parse("2026-04-07"),
							BigDecimal.valueOf(318.50)),
					new RentDto(
							UUID.fromString("770e8400-e29b-41d4-a716-446655440002"),
							AVAILABLE_MOSCOW_CAR_ID,
							UUID.fromString("660e8400-e29b-41d4-a716-446655440001"),
							LocalDate.parse("2026-03-01"),
							LocalDate.parse("2026-03-05"),
							BigDecimal.valueOf(250)));
		}

		private static CarDto car(UUID id, String model, String city) {
			return new CarDto(
					id,
					"VIN" + id.toString().substring(0, 8),
					model,
					"Black",
					BigDecimal.valueOf(50),
					city,
					"Central");
		}
	}
}
