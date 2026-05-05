package rental.additional.service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import rental.additional.dto.CarDto;

@Service
public class CarCacheService {

	private static final Logger log = LoggerFactory.getLogger(CarCacheService.class);

	private final ConcurrentHashMap<UUID, CarDto> byId = new ConcurrentHashMap<>();

	public void reloadFrom(List<CarDto> cars) {
		byId.clear();
		for (CarDto car : cars) {
			if (car != null && car.id() != null) {
				byId.put(car.id(), car);
			}
		}
	}

	public List<CarDto> getAll() {
		return List.copyOf(byId.values());
	}

	public int size() {
		return byId.size();
	}

	public void clear() {
		byId.clear();
	}

	@Scheduled(fixedRateString = "${cache.statistics.log-interval-ms:10000}")
	public void logCacheSize() {
		log.info("[car-cache-statistics] Car cache size={}", size());
	}
}
