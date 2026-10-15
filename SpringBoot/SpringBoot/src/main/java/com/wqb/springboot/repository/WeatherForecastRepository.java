package com.wqb.springboot.repository;

import com.wqb.springboot.entity.Village;
import com.wqb.springboot.entity.WeatherForecast;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface WeatherForecastRepository extends JpaRepository<WeatherForecast, Long> {

    List<WeatherForecast> findByVillageOrderByForecastDateAsc(Village village);
    List<WeatherForecast> findByVillageAndForecastDate(Village village, LocalDate date);

    long countByVillage(Village village);

    void deleteByVillage(Village village);
}
