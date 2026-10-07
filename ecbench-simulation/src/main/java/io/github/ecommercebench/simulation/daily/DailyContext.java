package io.github.ecommercebench.simulation.daily;

import io.github.ecommercebench.domain.catalog.CatalogData;
import io.github.ecommercebench.domain.catalog.Product;
import io.github.ecommercebench.domain.random.RandomStreams;
import io.github.ecommercebench.simulation.demand.DemandModel;
import java.time.LocalDate;
import java.util.Map;

/** 日切处理器共享的只读依赖。 */
public record DailyContext(
    LocalDate day,
    CatalogData catalog,
    Map<String, Product> products,
    RandomStreams randomStreams,
    DemandModel demandModel) {}
