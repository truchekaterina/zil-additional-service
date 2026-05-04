package rental.additional.client;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import rental.additional.dto.CarDto;
import rental.additional.dto.ClientDto;
import rental.additional.dto.RentDto;
import rental.additional.observability.ObservabilityService;

@Component
public class RestTemplateMainCrudClient implements MainCrudClient {

	private static final String HTTP_MAIN_CARS = "http.main.cars";
	private static final String HTTP_MAIN_CLIENTS = "http.main.clients";
	private static final String HTTP_MAIN_RENTS = "http.main.rents";

	private final RestTemplate restTemplate;
	private final String baseUrl;
	private final ObservabilityService observabilityService;

	public RestTemplateMainCrudClient(
			RestTemplate restTemplate,
			@Value("${main-service.base-url}") String baseUrl,
			ObservabilityService observabilityService) {
		this.restTemplate = restTemplate;
		this.baseUrl = baseUrl.replaceAll("/+$", "");
		this.observabilityService = observabilityService;
	}

	@Override
	public List<CarDto> getCars() {
		return getList("/cars", CarDto[].class, HTTP_MAIN_CARS);
	}

	@Override
	public List<ClientDto> getClients() {
		return getList("/clients", ClientDto[].class, HTTP_MAIN_CLIENTS);
	}

	@Override
	public List<RentDto> getRents() {
		return getList("/rents", RentDto[].class, HTTP_MAIN_RENTS);
	}

	private <T> List<T> getList(String path, Class<T[]> responseType, String category) {
		return observabilityService.timed(category, () -> {
			T[] response = restTemplate.getForObject(baseUrl + path, responseType);
			if (response == null) {
				return List.of();
			}
			return Arrays.asList(response);
		});
	}
}
