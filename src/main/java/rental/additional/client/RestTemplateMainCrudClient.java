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
		return timedList("/cars", CarDto[].class);
	}

	@Override
	public List<ClientDto> getClients() {
		return timedList("/clients", ClientDto[].class);
	}

	@Override
	public List<RentDto> getRents() {
		return timedList("/rents", RentDto[].class);
	}

	private <T> List<T> timedList(String path, Class<T[]> responseType) {
		long t0 = System.nanoTime();
		try {
			T[] response = restTemplate.getForObject(baseUrl + path, responseType);
			if (response == null) {
				return List.of();
			}
			return Arrays.asList(response);
		} finally {
			observabilityService.record("http.main.GET_" + path.replace('/', '_'), System.nanoTime() - t0);
		}
	}
}
