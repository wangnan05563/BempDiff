package com.demo;

public class OrderService {
    public int total(int price, int count) {
        if (price < 0 || count < 0) {
            throw new IllegalArgumentException("negative");
        }
        return price * count;
    }
}
