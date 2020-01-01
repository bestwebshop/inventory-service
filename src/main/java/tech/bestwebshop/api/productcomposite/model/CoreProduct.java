package tech.bestwebshop.api.productcomposite.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;

@Data
@RequiredArgsConstructor
@NoArgsConstructor
public class CoreProduct implements Serializable {

    @NonNull
    private int id;
    @NonNull
    private String name;
    @NonNull
    private double price;
    @NonNull
    private int categoryID;
    @NonNull
    private String details;
}
