package tech.bestwebshop.api.productcomposite.model;

import lombok.*;

@Data
@RequiredArgsConstructor
@NoArgsConstructor
public class Product {

    @NonNull
    @Generated
    private int id;
    @NonNull
    private String name;
    @NonNull
    private double price;
    @NonNull
    private Category category;
    @NonNull
    private String details;
}
