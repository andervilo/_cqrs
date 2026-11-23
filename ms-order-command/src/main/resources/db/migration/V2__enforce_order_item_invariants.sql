-- Deferred validation allows inserting the order and its items in one transaction.
-- Lock the aggregate row before counting to serialize concurrent modifications.
CREATE FUNCTION order_command.validate_order_items() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    affected_ids UUID[];
    target_id UUID;
    expected_total NUMERIC;
    actual_total NUMERIC;
    item_count BIGINT;
BEGIN
    IF TG_TABLE_NAME = 'orders' THEN
        affected_ids := ARRAY[NEW.id];
    ELSIF TG_OP = 'INSERT' THEN
        affected_ids := ARRAY[NEW.order_id];
    ELSIF TG_OP = 'DELETE' THEN
        affected_ids := ARRAY[OLD.order_id];
    ELSE
        affected_ids := ARRAY[OLD.order_id, NEW.order_id];
    END IF;
    FOREACH target_id IN ARRAY affected_ids LOOP
        SELECT total_amount INTO expected_total FROM order_command.orders WHERE id = target_id FOR UPDATE;
        IF FOUND THEN
            SELECT count(*), coalesce(sum(quantity * unit_price), 0)
                INTO item_count, actual_total FROM order_command.item WHERE order_id = target_id;
            IF item_count < 1 OR item_count > 1000 OR actual_total <> expected_total THEN
                RAISE EXCEPTION 'Order must contain 1..1000 items and a consistent total'
                    USING ERRCODE = '23514', CONSTRAINT = 'ck_order_items_total';
            END IF;
        END IF;
    END LOOP;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ck_order_items
AFTER INSERT OR UPDATE OR DELETE ON item DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION order_command.validate_order_items();

CREATE CONSTRAINT TRIGGER ck_order_total
AFTER INSERT OR UPDATE ON orders DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION order_command.validate_order_items();
