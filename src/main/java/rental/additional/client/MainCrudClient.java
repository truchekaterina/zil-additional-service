package rental.additional.client;

import java.util.List;

import rental.additional.dto.CarDto;
import rental.additional.dto.ClientDto;
import rental.additional.dto.RentDto;

public interface MainCrudClient {

	List<CarDto> getCars();

	List<ClientDto> getClients();

	List<RentDto> getRents();
}
