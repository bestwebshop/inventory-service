package tech.bestwebshop.api.productcomposite.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Positive;

@Data
@NoArgsConstructor
public class ProductDTO {

    @NotEmpty
    @NonNull
    private String name;
    @Positive
    @NonNull
    private double price;
    @NotEmpty
    @NonNull
    private String category;
    @NotEmpty
    @NonNull
    private String details;


}
