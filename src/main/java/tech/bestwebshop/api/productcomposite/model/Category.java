package tech.bestwebshop.api.productcomposite.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@Data
@NoArgsConstructor
@RequiredArgsConstructor
public class Category {

    @NonNull
    private int id;
    @NonNull
    private String name;
}
