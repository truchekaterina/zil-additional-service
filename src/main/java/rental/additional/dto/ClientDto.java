package rental.additional.dto;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ClientDto(
		UUID id,
		String fullName,
		String driverLicense,
		String phone) {
}
