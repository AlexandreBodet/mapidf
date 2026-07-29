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

@Getter
@ToString
@Entity
@Table(name = "stop_time")
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode
public class StopTime {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "branch_id")
    @ToString.Exclude
    private Branch branch;

    @ManyToOne
    @JoinColumn(name = "stop_id")
    @ToString.Exclude
    private Stop stop;

    @Column(name = "stop_sequence")
    private int stopSequence;

    @Column(name = "arrival_sec")
    private int arrivalSec;

    @Column(name = "departure_sec")
    private int departureSec;
}
