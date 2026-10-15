package com.wqb.springboot.repository;

import com.wqb.springboot.entity.Village;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VillageRepository extends JpaRepository<Village, Long> {

    List<Village> findAllByOrderByIdAsc();

    Optional<Village> findByCode(String code);
}
