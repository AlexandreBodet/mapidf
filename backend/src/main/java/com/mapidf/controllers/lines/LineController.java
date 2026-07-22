package com.mapidf.controllers.lines;

import com.mapidf.services.NetworkQueryService;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/lines")
@AllArgsConstructor
public class LineController {

    private final NetworkQueryService networkQueryService;

    @GetMapping("/{id}/shape")
    public ShapeResponse shape(@PathVariable String id) {
        return networkQueryService.getShape(id);
    }
}
