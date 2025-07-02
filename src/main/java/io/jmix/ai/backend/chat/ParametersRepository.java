package io.jmix.ai.backend.chat;

import io.jmix.ai.backend.entity.Parameters;
import io.jmix.core.repository.JmixDataRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ParametersRepository extends JmixDataRepository<Parameters, UUID>, ParametersRepositoryExt {
}