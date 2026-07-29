package com.mapidf.data.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.locationtech.jts.geom.LineString;

/**
 * Une branche d'une ligne : un tracé, un sens, son terminus. Une ligne simple en a une par
 * sens ; la 7 et la 13 en ont deux par sens, la 10 deux dans un sens.
 */
@Getter
@ToString
@Entity
@Table(name = "branch")
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode
public class Branch {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "route_id")
    @ToString.Exclude
    private Route route;

    @Column(name = "gtfs_shape_id")
    private String gtfsShapeId;

    /** {@code trip_id} GTFS de la course représentative retenue — traçabilité seulement. */
    @Column(name = "representative_trip")
    private String representativeTrip;

    @Column(name = "direction")
    private Short direction;

    @Column(name = "terminus_name")
    private String terminusName;

    @Column(name = "geom", columnDefinition = "geometry(LineString,4326)")
    private LineString geom;
}
