package com.mapidf.data.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.locationtech.jts.geom.Point;

@Getter
@ToString
@Entity
@Table(name = "stop")
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode
public class Stop {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "gtfs_id")
    private String gtfsId;

    @Column(name = "name")
    private String name;

    @Column(name = "geom", columnDefinition = "geometry(Point,4326)")
    private Point geom;
}
