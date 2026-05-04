package rental.additional.service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import rental.additional.dto.ClientDto;

@Service
public class ClientCacheService {

	private static final Logger log = LoggerFactory.getLogger(ClientCacheService.class);

	private final ConcurrentHashMap<UUID, ClientDto> byId = new ConcurrentHashMap<>();

	public void reloadFrom(List<ClientDto> clients) {
		byId.clear();
		for (ClientDto client : clients) {
			if (client != null && client.id() != null) {
				byId.put(client.id(), client);
			}
		}
	}

	public ClientDto get(UUID id) {
		if (id == null) {
			return null;
		}
		return byId.get(id);
	}

	public int size() {
		return byId.size();
	}

	public void clear() {
		byId.clear();
	}

	@Scheduled(fixedRateString = "${cache.statistics.log-interval-ms:10000}")
	public void logCacheSize() {
		log.info("[client-cache-statistics] Client cache size={}", size());
	}
}
