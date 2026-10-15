package com.wqb.springboot.repository;

import com.wqb.springboot.entity.Village;
import com.wqb.springboot.entity.WeatherObservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WeatherObservationRepository extends JpaRepository<WeatherObservation, Long> {

    Optional<WeatherObservation> findFirstByVillageOrderByObservationTimeDesc(Village village);

    long countByVillage(Village village);

    void deleteByVillage(Village village);
}
