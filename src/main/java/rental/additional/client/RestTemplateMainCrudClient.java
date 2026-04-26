package rental.additional.client;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import rental.additional.dto.CarDto;
import rental.additional.dto.ClientDto;
import rental.additional.dto.RentDto;

@Component
public class RestTemplateMainCrudClient implements MainCrudClient {

	private final RestTemplate restTemplate;
	private final String baseUrl;

	public RestTemplateMainCrudClient(
			RestTemplate restTemplate,
			@Value("${main-service.base-url}") String baseUrl) {
		this.restTemplate = restTemplate;
		this.baseUrl = baseUrl.replaceAll("/+$", "");
	}

	@Override
	public List<CarDto> getCars() {
		return getList("/cars", CarDto[].class);
	}

	@Override
	public List<ClientDto> getClients() {
		return getList("/clients", ClientDto[].class);
	}

	@Override
	public List<RentDto> getRents() {
		return getList("/rents", RentDto[].class);
	}

	private <T> List<T> getList(String path, Class<T[]> responseType) {
		T[] response = restTemplate.getForObject(baseUrl + path, responseType);
		if (response == null) {
			return List.of();
		}
		return Arrays.asList(response);
	}
}
