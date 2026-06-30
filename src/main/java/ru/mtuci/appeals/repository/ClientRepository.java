package ru.mtuci.appeals.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.mtuci.appeals.domain.Client;

import java.util.UUID;

public interface ClientRepository extends JpaRepository<Client, UUID> {
}
